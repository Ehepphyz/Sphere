package com.sphere.core.python.jupyterlab.kernel;

import com.sphere.utils.AppLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Python side of the kernel. Carried here so the notebook cannot break by
 * losing a file, and written out to config/ so it stays readable and adaptable.
 */
public final class KernelScript {

    /** Bumped whenever the source below changes, so a stale copy is rewritten. */
    public static final int VERSION = com.sphere.Sphere.JUPYTER_KERNEL_VERSION;

    public static final String FILE_NAME = "jupylab_kernel.py";

    /** Bumped whenever SHM_SOURCE below changes. */
    public static final int SHM_VERSION = com.sphere.Sphere.JUPYTER_SHM_MODULE_VERSION;

    public static final String SHM_FILE_NAME = "sphere_shm.py";

    private KernelScript() {
    }

    /**
     * Path of the driver on disk, written or refreshed as needed. The version
     * marker on the first lines is what tells a stale copy from a current one.
     */
    public static Path materialize() throws IOException {
        Path directory = Path.of("config");
        Files.createDirectories(directory);
        Path script = directory.resolve(FILE_NAME);
        if (Files.isReadable(script)) {
            String existing = Files.readString(script, StandardCharsets.UTF_8);
            if (existing.contains("SPHERE_KERNEL_VERSION = " + VERSION)) {
                return script;
            }
            //AppLogger.info("JupyLab kernel driver refreshed to version " + VERSION + ".");
        }
        Files.writeString(script, SOURCE.replace("@SPHERE_KERNEL_VERSION@", String.valueOf(VERSION)), StandardCharsets.UTF_8);
        return script;
    }

    /**
     * Writes the shared-memory module beside the driver, which imports it from
     * its own directory. A kernel started without a region still runs: the module
     * is there, it simply has nothing to attach to.
     */
    public static Path materializeShm() throws IOException {
        Path directory = Path.of("config");
        Files.createDirectories(directory);
        Path module = directory.resolve(SHM_FILE_NAME);
        if (Files.isReadable(module)) {
            String existing = Files.readString(module, StandardCharsets.UTF_8);
            // stale too when the backend's layout moved on: the module mirrors its version
            if (existing.contains("SPHERE_SHM_VERSION = " + SHM_VERSION)
                && existing.contains("\nSHM_VERSION = " + com.sphere.Sphere.ROOT_SHM_VERSION + "\n")) {
                return module;
            }
            //AppLogger.info("Sphere shared-memory module refreshed to version " + SHM_VERSION + ".");
        }
        Files.writeString(module, shmSource(), StandardCharsets.UTF_8);
        return module;
    }

    /** The module with its own version and the backend's layout version filled in. */
    private static String shmSource() {
        return SHM_SOURCE.replace("@SPHERE_SHM_VERSION@", String.valueOf(SHM_VERSION))
            .replace("@ROOT_SHM_VERSION@", String.valueOf(com.sphere.Sphere.ROOT_SHM_VERSION));
    }

    public static final String IPY_FILE_NAME = "sphere_ipykernel.py";
    public static final String TOOLS_FILE_NAME = "sphere_kernel_tools.py";

    /** The bootstrap an ipykernel is started through, beside the driver. */
    public static Path materializeIpykernel() throws IOException {
        return write(IPY_FILE_NAME, "SPHERE_IPYKERNEL_VERSION = ",
                     com.sphere.Sphere.JUPYTER_IPYKERNEL_BOOTSTRAP_VERSION,
                     IPY_SOURCE.replace("@SPHERE_IPYKERNEL_VERSION@",
                                        String.valueOf(com.sphere.Sphere.JUPYTER_IPYKERNEL_BOOTSTRAP_VERSION)));
    }

    /** The namespace and analysis helpers both kernels import. */
    public static Path materializeTools() throws IOException {
        return write(TOOLS_FILE_NAME, "SPHERE_TOOLS_VERSION = ", com.sphere.Sphere.JUPYTER_TOOLS_VERSION,
                     TOOLS_SOURCE.replace("@SPHERE_TOOLS_VERSION@",
                                          String.valueOf(com.sphere.Sphere.JUPYTER_TOOLS_VERSION)));
    }

    /** Writes a file of config/ unless a copy carrying the same version marker is already there. */
    private static Path write(String name, String marker, int version, String source) throws IOException {
        Path directory = Path.of("config");
        Files.createDirectories(directory);
        Path file = directory.resolve(name);
        if (Files.isReadable(file)) {
            String existing = Files.readString(file, StandardCharsets.UTF_8);
            if (existing.contains(marker + version + "\n")) {
                return file;
            }
            //AppLogger.info("JupyLab " + name + " refreshed to version " + version + ".");
        }
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return file;
    }

    private static final String SOURCE = """
# Sphere JupyLab kernel driver.
#
# Keeps one interpreter alive for the whole notebook and talks to Sphere over
# stdin/stdout, one JSON message per line. No Jupyter, ipykernel or ZMQ needed.
# Regenerated by Sphere when missing; edit only if you know what you are doing.
SPHERE_KERNEL_VERSION = @SPHERE_KERNEL_VERSION@

import _thread
import ast
import base64
import builtins
import io
import json
import linecache
import os
import queue
import signal
import sys
import threading
import time
import traceback

_PROTOCOL = sys.__stdout__
_LOCK = threading.RLock()


def _send(message):
    # One JSON object per line on the real stdout, which only we write to.
    with _LOCK:
        _PROTOCOL.write(json.dumps(message, ensure_ascii=False) + "\\n")
        _PROTOCOL.flush()


class _StreamRedirect(io.TextIOBase):
    # User prints become stream messages instead of raw pipe bytes. Writes are
    # joined until a newline: print('a =', 1) reaches write() four times, and one
    # message per fragment would mean four repaints for one line of output.
    #

    LIMIT = 8192

    def __init__(self, name):
        self.name_ = name
        self.exec_id = None
        self._buffer = []
        self._size = 0

    def write(self, text):
        if not text:
            return 0
        self._buffer.append(text)
        self._size += len(text)
        if "\\n" in text or self._size >= self.LIMIT:
            self.flush()
        return len(text)

    def flush(self):
        if not self._buffer:
            return None
        text = "".join(self._buffer)
        self._buffer = []
        self._size = 0
        _send({"t": "stream", "id": self.exec_id, "name": self.name_, "text": text})
        return None

    def isatty(self):
        return False


_stdout = _StreamRedirect("stdout")
_stderr = _StreamRedirect("stderr")
sys.stdout = _stdout
sys.stderr = _stderr

_input_replies = queue.Queue()


def _input(prompt=""):
    # input() used to block forever on a pipe nobody wrote to.
    _stdout.flush()
    _send({"t": "input", "id": _stdout.exec_id, "prompt": str(prompt)})
    return _input_replies.get()


builtins.input = _input

_namespace = {"__name__": "__main__", "__builtins__": builtins}
_execution_count = 0
_requests = queue.Queue()


def _register_source(cell_name, code):
    # linecache lets a traceback show the offending line of the cell itself.
    lines = [line + "\\n" for line in code.split("\\n")]
    linecache.cache[cell_name] = (len(code), None, lines, cell_name)


def _rich(value):
    # The mime bundle for a value, richest form first.
    bundle = {}
    for method, mime in (("_repr_html_", "text/html"),
                         ("_repr_latex_", "text/latex"),
                         ("_repr_svg_", "image/svg+xml")):
        renderer = getattr(value, method, None)
        if callable(renderer):
            try:
                rendered = renderer()
                if rendered:
                    bundle[mime] = rendered
            except Exception:
                pass
    png = getattr(value, "_repr_png_", None)
    if callable(png):
        try:
            raw = png()
            if raw:
                bundle["image/png"] = base64.b64encode(raw).decode("ascii")
        except Exception:
            pass
    try:
        bundle["text/plain"] = repr(value)
    except Exception:
        bundle["text/plain"] = "<unrepresentable object>"
    return bundle


def _flush_figures(exec_id):
    # Matplotlib figures used to travel as a sentinel inside stdout.
    plt = sys.modules.get("matplotlib.pyplot")
    if plt is None:
        return
    try:
        for number in plt.get_fignums():
            figure = plt.figure(number)
            buffer = io.BytesIO()
            figure.savefig(buffer, format="png", bbox_inches="tight")
            _send({"t": "display", "id": exec_id, "bundle":
                   {"image/png": base64.b64encode(buffer.getvalue()).decode("ascii")}})
        plt.close("all")
    except Exception:
        pass


def _format_error(cell_name, exc_type, exc_value, tb):
    # The driver's own frame is dropped so the trace starts in the user's cell.
    frames = tb.tb_next if tb is not None and tb.tb_next is not None else tb
    lines = traceback.format_exception(exc_type, exc_value, frames)
    return {"ename": exc_type.__name__,
            "evalue": str(exc_value),
            "traceback": [line for line in
                          "".join(lines).rstrip("\\n").split("\\n")]}


def _execute(exec_id, code):
    global _execution_count
    cell_name = "<cell %d>" % exec_id
    _register_source(cell_name, code)
    _stdout.exec_id = exec_id
    _stderr.exec_id = exec_id
    started = time.perf_counter()
    try:
        tree = ast.parse(code, filename=cell_name, mode="exec")
    except SyntaxError:
        _send(dict({"t": "error", "id": exec_id},
                   **_format_error(cell_name, *sys.exc_info())))
        _send({"t": "done", "id": exec_id, "count": None,
               "ms": int((time.perf_counter() - started) * 1000)})
        return

    tail = None
    # A cell ending in an expression displays its value, the way a notebook does.
    if tree.body and isinstance(tree.body[-1], ast.Expr):
        tail = ast.Expression(tree.body.pop().value)
        ast.copy_location(tail, tail.body)

    _execution_count += 1
    count = _execution_count
    try:
        if tree.body:
            exec(compile(tree, cell_name, "exec"), _namespace)
        if tail is not None:
            value = eval(compile(tail, cell_name, "eval"), _namespace)
            if value is not None:
                _namespace["_"] = value
                _send({"t": "result", "id": exec_id, "count": count,
                       "bundle": _rich(value)})
    except KeyboardInterrupt:
        _send({"t": "error", "id": exec_id, "ename": "KeyboardInterrupt",
               "evalue": "execution interrupted", "traceback": []})
    except BaseException:
        _send(dict({"t": "error", "id": exec_id},
                   **_format_error(cell_name, *sys.exc_info())))
    finally:
        _stdout.flush()
        _stderr.flush()
        _flush_figures(exec_id)
        _send({"t": "done", "id": exec_id, "count": count,
               "ms": int((time.perf_counter() - started) * 1000)})


def _complete(exec_id, code, cursor):
    # Completion asks the live namespace, so it knows what the objects hold.
    import re
    import rlcompleter
    prefix = code[:cursor]
    match = re.search(r"[A-Za-z_][A-Za-z0-9_\\.]*$", prefix)
    token = match.group(0) if match else ""
    completer = rlcompleter.Completer(_namespace)
    matches = []
    try:
        index = 0
        while True:
            candidate = completer.complete(token, index)
            if candidate is None or index > 200:
                break
            if candidate not in matches:
                matches.append(candidate)
            index += 1
    except Exception:
        pass
    _send({"t": "complete", "id": exec_id, "start": cursor - len(token),
           "matches": matches})


def _describe(value):
    # A short shape for the inspector, without importing numpy or pandas.
    kind = type(value).__name__
    try:
        shape = getattr(value, "shape", None)
        if shape is not None:
            return kind, "%s %s" % (getattr(getattr(value, "dtype", ""), "name", ""),
                                    tuple(shape))
        if isinstance(value, (list, tuple, set, dict)):
            return kind, "%d elements" % len(value)
        text = repr(value)
        return kind, text if len(text) <= 80 else text[:77] + "..."
    except Exception:
        return kind, ""


def _variables(exec_id):
    items = []
    for name, value in list(_namespace.items()):
        if name.startswith("__") or name == "_":
            continue
        import types
        if isinstance(value, types.ModuleType) or callable(value) and isinstance(
                value, (types.FunctionType, types.BuiltinFunctionType, type)):
            continue
        kind, info = _describe(value)
        items.append({"name": name, "type": kind, "info": info})
    items.sort(key=lambda item: item["name"])
    _send({"t": "vars", "id": exec_id, "items": items})


def _analyze(exec_id, cells):
    # Undefined names, cells that depend on run order, live kinds for the
    # highlighter: sphere_kernel_tools reads the namespace without changing it.
    try:
        import sphere_kernel_tools
        result = json.loads(sphere_kernel_tools.analyze(list(cells), _namespace).text)
    except Exception:
        result = []
    _send({"t": "analysis", "id": exec_id, "cells": result})


def _interrupt():
    # Stops the running cell. The interrupt has to reach the process, not just the
    # thread that asks for it: interrupt_main and raise_signal only set a flag the
    # main thread reads at its next check, so a cell blocked in time.sleep kept
    # sleeping to the end. A real SIGINT breaks the blocking call itself.
    #
    try:
        if os.name == "nt":
            # No process-directed signal on Windows; CPython's own handler wakes
            # the sleeping main thread through its interrupt event.
            signal.raise_signal(signal.SIGINT)
        else:
            os.kill(os.getpid(), signal.SIGINT)
    except Exception:
        _thread.interrupt_main()


def _reset():
    global _namespace, _execution_count
    _namespace = {"__name__": "__main__", "__builtins__": builtins}
    _execution_count = 0
    _send({"t": "reset"})


_shm_replies = {}
_shm_event = threading.Event()


def _shm_wait_for(rid, timeout=10.0):
    # The reply arrives on the reader thread, so this waits rather than reads.
    deadline = time.monotonic() + timeout
    while True:
        if rid in _shm_replies:
            return _shm_replies.pop(rid)
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError(f"Sphere did not answer shared-memory request {rid}")
        _shm_event.wait(remaining)
        _shm_event.clear()


def _install_shm():
    # Optional: a kernel started without a shared region simply has no module.
    if not os.environ.get("SPHERE_SHM_PATH"):
        return
    try:
        import sphere_shm
    except ImportError:
        return
    sphere_shm.set_broker_factory(
        lambda: sphere_shm.KernelBroker(_send, _shm_wait_for))
    builtins.sphere_shm = sphere_shm


def _command_stream():
    # Sphere's commands are read from a copy of the pipe, and the process's own
    # stdin is pointed at the null device. On Windows the pipe is a synchronous
    # handle: while the reader below waits in a read on it, anything else that
    # touches the same pipe -- a library asking sys.stdin.isatty() while it is
    # imported, as matplotlib's imports do -- waits as well, until Sphere next
    # writes. The first "import matplotlib.pyplot" of a session then hung until
    # the next command. Libraries now ask the null device and are answered at
    # once, user code reading stdin gets an end of file instead of a hang, and
    # the commands are read as the UTF-8 Sphere writes rather than the locale's
    # code page.
    try:
        fd = os.dup(0)
        null = os.open(os.devnull, os.O_RDONLY)
        os.dup2(null, 0)
        os.close(null)
        sys.stdin = open(0, "r", closefd=False)
        return open(fd, "r", encoding="utf-8", errors="replace")
    except OSError:
        return sys.stdin


def _read_commands(commands):
    # Reads Sphere's messages while a cell is running. Everything that must reach a
    # busy kernel -- an interrupt, an answer to input() -- arrives on this thread.
    #
    for line in commands:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except Exception:
            continue
        op = message.get("op")
        if op == "shm_reply":
            _shm_replies[message.get("rid")] = message
            _shm_event.set()
            continue
        if op == "exec":
            _requests.put(message)
        elif op == "input":
            _input_replies.put(message.get("text", ""))
        elif op == "interrupt":
            _interrupt()
        elif op == "complete":
            _complete(message.get("id"), message.get("code", ""),
                      message.get("cursor", 0))
        elif op == "vars":
            _variables(message.get("id"))
        elif op == "analyze":
            _analyze(message.get("id"), message.get("cells") or [])
        elif op == "reset":
            _requests.put({"op": "reset"})
        elif op == "shutdown":
            _requests.put({"op": "shutdown"})
            return
    _requests.put({"op": "shutdown"})
    # The pipe closed without a shutdown: Sphere is gone. A cell still running
    # (a plt.show() waiting on a window, a long loop) would keep this process
    # alive with nobody to read it; it gets a moment to end, then the process goes.
    timer = threading.Timer(3.0, os._exit, args=(0,))
    timer.daemon = True
    timer.start()


def main():
    commands = _command_stream()
    # The notebook's folder first, as in Jupyter, so the modules beside it
    # import; Sphere's helpers (sphere_shm, sphere_kernel_tools) last.
    sys.path[0] = os.getcwd()
    sys.path.append(os.path.dirname(os.path.abspath(__file__)))
    _install_shm()
    _send({"t": "ready", "version": SPHERE_KERNEL_VERSION,
           "python": sys.version.split()[0], "executable": sys.executable})
    reader = threading.Thread(target=_read_commands, args=(commands,), name="command-reader", daemon=True)
    reader.start()
    while True:
        try:
            request = _requests.get()
        except KeyboardInterrupt:
            # An interrupt that arrived between two cells has nothing to stop.
            continue
        op = request.get("op")
        if op == "shutdown":
            return
        if op == "reset":
            _reset()
        elif op == "exec":
            _execute(request.get("id"), request.get("code", ""))


if __name__ == "__main__":
    main()
""";

    private static final String SHM_SOURCE = """
# Sphere shared-memory bridge for the notebook kernel.
#
# Turns a block of the engine's shared region into a numpy array without copying
# a byte, and hands it to PyTorch, TensorFlow or JAX through DLPack. Blocks are
# obtained from and returned to the Java side, which owns the allocator: writing
# a third lock-free allocator in Python would be one implementation too many, and
# the round trip costs a fraction of a millisecond against a transfer that would
# otherwise copy megabytes.
#
# Regenerated by Sphere when missing; edit only if you know what you are doing.
SPHERE_SHM_VERSION = @SPHERE_SHM_VERSION@

import mmap
import os
import struct
import weakref

# ---------------------------------------------------------------------------
# Region layout, mirroring inc/shm_layout.h and inc/common_config.h
# ---------------------------------------------------------------------------

SHM_MAGIC = 0x53504852
SHM_VERSION = @ROOT_SHM_VERSION@
CHUNK_MAGIC = 0x43484E4B
CHUNK_HEADER_SIZE = 128
CHUNK_FREE = 8
SHM_OFFSET_SHIFT = 6

HDR_MAGIC_OFF = 0
HDR_VERSION_OFF = 4
HDR_OFF_DATA_HEAP = 584

CHUNK_MAGIC_OFF = 0
CHUNK_SIZE_OFF = 4
CHUNK_KIND_OFF = 16
CHUNK_FLAGS_OFF = 22
CHUNK_GENERATION_OFF = 28

# ShmDType in inc/lockfree_ring.h, paired with what each framework calls it.
# bfloat16 has no numpy dtype of its own; ml_dtypes supplies one when installed,
# and without it a bfloat16 block is refused rather than silently misread.
_DTYPE_TABLE = {
    0: ("float32", 4),
    1: ("float16", 2),
    2: ("int32", 4),
    3: ("int64", 8),
    4: ("uint8", 1),
    5: ("bfloat16", 2),
    6: ("float64", 8),
    7: ("uint32", 4),
    8: ("uint64", 8),
    9: ("int16", 2),
    10: ("uint16", 2),
    11: ("int8", 1),
}

_DTYPE_CODES = {name: code for code, (name, _) in _DTYPE_TABLE.items()}


class ShmError(RuntimeError):
    pass


def _numpy():
    import numpy as np
    return np


def _numpy_dtype(name):
    np = _numpy()
    if name == "bfloat16":
        try:
            import ml_dtypes
        except ImportError as exc:
            raise ShmError(
                "bfloat16 blocks need the ml_dtypes package, which gives numpy a "
                "bfloat16 dtype; PyTorch and TensorFlow read it through DLPack "
                "once it is installed."
            ) from exc
        return ml_dtypes.bfloat16
    return np.dtype(name)


# ---------------------------------------------------------------------------
# Blocks
# ---------------------------------------------------------------------------

class ShmArray:
    \"\"\"Placeholder replaced below once numpy is available.\"\"\"


def _shm_array_class():
    \"\"\"The ndarray subclass that carries the owning block.

    A plain ndarray takes no attributes, and the block has to stay reachable for
    as long as any view of it does: the release fires when the last one goes.
    \"\"\"
    global ShmArray
    if not isinstance(ShmArray, type) or not hasattr(ShmArray, "__array_finalize__"):
        np = _numpy()

        class _ShmArray(np.ndarray):
            block = None

            def __array_finalize__(self, obj):
                if obj is not None:
                    self.block = getattr(obj, "block", None)

        ShmArray = _ShmArray
    return ShmArray


class Block:
    \"\"\"One block of the shared region, alive for as long as anything reads it.

    numpy keeps this object referenced through ``array.base``, and every tensor
    built from that array keeps the array. So the block is returned to the engine
    when the last view goes away, not when this object leaves scope.
    \"\"\"

    __slots__ = ("_region", "_offset", "_generation", "_nbytes", "_dtype",
                 "_shape", "_view", "_finalizer", "__weakref__")

    def __init__(self, region, offset, generation, nbytes, dtype, shape, on_release):
        self._region = region
        self._offset = offset
        self._generation = generation
        self._nbytes = nbytes
        self._dtype = dtype
        self._shape = tuple(shape)
        self._view = memoryview(region.map)[offset:offset + nbytes]
        self._finalizer = weakref.finalize(self, on_release, offset, generation)

    @property
    def offset(self):
        return self._offset

    @property
    def generation(self):
        return self._generation

    @property
    def dtype(self):
        return self._dtype

    @property
    def shape(self):
        return self._shape

    def is_live(self):
        return self._region.handle_is_live(self._offset, self._generation)

    def array(self):
        \"\"\"The block as a writable numpy array. No bytes are copied.\"\"\"
        np = _numpy()
        if not self.is_live():
            raise ShmError(
                f"block at offset {self._offset} was recycled; its generation is "
                f"no longer {self._generation}"
            )
        dtype = _numpy_dtype(self._dtype)
        shape = self._shape if self._shape else (self._nbytes // np.dtype(dtype).itemsize,)
        # ndarray over the mapped bytes rather than frombuffer, which would hand
        # back a read-only view and make the write direction impossible.
        flat = np.ndarray(shape, dtype=dtype, buffer=self._view)
        view = flat.view(_shm_array_class())
        view.block = self
        return view

    def torch(self):
        \"\"\"A torch tensor over the same bytes.\"\"\"
        import torch
        return torch.from_dlpack(self.array())

    def tensorflow(self):
        \"\"\"A TensorFlow tensor over the same bytes.\"\"\"
        import tensorflow as tf
        return tf.experimental.dlpack.from_dlpack(self.array().__dlpack__())

    def release(self):
        \"\"\"Returns the block now instead of waiting for the collector.\"\"\"
        self._view.release()
        self._finalizer()

    def __len__(self):
        return self._nbytes

    def __repr__(self):
        return (f"<Block offset={self._offset} generation={self._generation} "
                f"{self._dtype}{list(self._shape)}>")


# ---------------------------------------------------------------------------
# Region
# ---------------------------------------------------------------------------

class Region:
    \"\"\"The mapped shared region, plus the channel that asks Java for blocks.\"\"\"

    def __init__(self, path, broker=None):
        self.path = path
        self._fd = os.open(path, os.O_RDWR)
        self.map = mmap.mmap(self._fd, 0)
        self._broker = broker

        magic = struct.unpack_from("<I", self.map, HDR_MAGIC_OFF)[0]
        version = struct.unpack_from("<I", self.map, HDR_VERSION_OFF)[0]
        if magic != SHM_MAGIC or version != SHM_VERSION:
            # Sphere lays the region out before starting a kernel, so this only
            # happens when the module is pointed somewhere it should not be.
            raise ShmError(f"{path} is not a shared region this kernel can read")
        self.heap_offset = struct.unpack_from("<Q", self.map, HDR_OFF_DATA_HEAP)[0]

    def close(self):
        self.map.close()
        os.close(self._fd)

    def handle_is_live(self, offset, generation):
        \"\"\"True while `offset` still names the block it was handed out for.\"\"\"
        base = offset - CHUNK_HEADER_SIZE
        if base < 0 or offset > len(self.map):
            return False
        if struct.unpack_from("<I", self.map, base + CHUNK_MAGIC_OFF)[0] != CHUNK_MAGIC:
            return False
        live = struct.unpack_from("<I", self.map, base + CHUNK_GENERATION_OFF)[0]
        flags = struct.unpack_from("<H", self.map, base + CHUNK_FLAGS_OFF)[0]
        return live == generation and (flags & CHUNK_FREE) == 0

    def attach(self, offset, generation, dtype, shape):
        \"\"\"Wraps a block the engine has already filled, for reading.\"\"\"
        if isinstance(dtype, int):
            dtype = _DTYPE_TABLE[dtype][0]
        nbytes = _itemsize(dtype)
        for extent in shape:
            nbytes *= extent
        if not self.handle_is_live(offset, generation):
            raise ShmError(f"block at offset {offset} is no longer generation {generation}")
        return Block(self, offset, generation, nbytes, dtype, shape, self._release)

    def allocate(self, dtype, shape):
        \"\"\"Asks Java for a block and hands it back writable, for sending data out.\"\"\"
        if self._broker is None:
            raise ShmError("no channel to the Java side: this kernel cannot allocate")
        if isinstance(dtype, int):
            dtype = _DTYPE_TABLE[dtype][0]
        if dtype not in _DTYPE_CODES:
            raise ShmError(f"unknown dtype {dtype!r}")
        nbytes = _itemsize(dtype)
        for extent in shape:
            nbytes *= extent
        offset, generation = self._broker.allocate(nbytes, _DTYPE_CODES[dtype])
        if offset == 0:
            raise ShmError(f"the engine refused a block of {nbytes} bytes")
        return Block(self, offset, generation, nbytes, dtype, shape, self._release)

    def _release(self, offset, generation):
        if self._broker is not None:
            self._broker.release(offset, generation)


def _itemsize(dtype):
    for name, size in _DTYPE_TABLE.values():
        if name == dtype:
            return size
    raise ShmError(f"unknown dtype {dtype!r}")


# ---------------------------------------------------------------------------
# The channel to Java, over the kernel's own line protocol
# ---------------------------------------------------------------------------

class KernelBroker:
    \"\"\"Asks the Java side to allocate and free, over the stdio line protocol.

    Java owns the allocator, so the CAS that claims a block happens in exactly one
    place. Only the request travels; the data never leaves the shared region.
    \"\"\"

    def __init__(self, send, wait_for):
        self._send = send
        self._wait_for = wait_for
        self._next_id = 0

    def _request(self, message):
        self._next_id += 1
        message["rid"] = self._next_id
        self._send(message)
        return self._wait_for(self._next_id)

    def allocate(self, nbytes, dtype_code):
        reply = self._request({"t": "shm_alloc", "bytes": nbytes, "dtype": dtype_code})
        return int(reply.get("offset", 0)), int(reply.get("generation", 0))

    def release(self, offset, generation):
        self._send({"t": "shm_release", "offset": offset, "generation": generation})


_REGION = None


def region():
    \"\"\"The region this kernel is attached to, opened on first use.\"\"\"
    global _REGION
    if _REGION is None:
        path = os.environ.get("SPHERE_SHM_PATH")
        if not path:
            raise ShmError("SPHERE_SHM_PATH is not set: Sphere did not attach this kernel")
        _REGION = Region(path, broker=_default_broker())
    return _REGION


_BROKER_FACTORY = None


def set_broker_factory(factory):
    \"\"\"Installed by the kernel driver, which owns the line protocol.\"\"\"
    global _BROKER_FACTORY
    _BROKER_FACTORY = factory


def _default_broker():
    return _BROKER_FACTORY() if _BROKER_FACTORY is not None else None


def attach(offset, generation, dtype, shape):
    return region().attach(offset, generation, dtype, shape)


def allocate(dtype, shape):
    return region().allocate(dtype, shape)
""";

    private static final String IPY_SOURCE = """
# Sphere's bootstrap for an ipykernel.
#
# Starts the ipykernel of this environment, unchanged, and adds beside the
# Jupyter protocol what Sphere needs, over the process's own pipes:
#   - an interrupt that works on Windows, where ipykernel only takes one from an
#     event handle its parent created, which a Java program cannot hand over;
#   - the shared-memory bridge (sphere_shm), as in Sphere's own driver;
#   - an exit when Sphere goes away, rather than an interpreter left running.
#
# Regenerated by Sphere when missing; edit only if you know what you are doing.
SPHERE_IPYKERNEL_VERSION = @SPHERE_IPYKERNEL_VERSION@

import json
import os
import signal
import sys
import threading
import time

_HERE = os.path.dirname(os.path.abspath(__file__))
_LOCK = threading.Lock()
_EVENTS = None
_shm_replies = {}
_shm_event = threading.Event()


def _send(message):
    # One JSON object per line on the copy of stdout Sphere reads.
    with _LOCK:
        _EVENTS.write(json.dumps(message) + "\\n")
        _EVENTS.flush()


def _channel():
    # Sphere writes commands on stdin and reads events on stdout. Copies of both
    # are taken before ipykernel starts; stdin itself is pointed at the null
    # device, because on Windows a read pending on a synchronous pipe also blocks
    # anything else touching it (a library asking sys.stdin.isatty() on import).
    commands = os.dup(0)
    events = os.dup(1)
    null = os.open(os.devnull, os.O_RDONLY)
    os.dup2(null, 0)
    os.close(null)
    sys.stdin = open(0, "r", closefd=False)
    return (open(commands, "r", encoding="utf-8", errors="replace"),
            open(events, "w", encoding="utf-8"))


def _interrupt():
    # A real SIGINT raised in this process. ipykernel ignores SIGINT between
    # cells and turns it into KeyboardInterrupt while one runs; on Windows the
    # signal also wakes a time.sleep, which _thread.interrupt_main does not.
    try:
        if os.name == "nt":
            signal.raise_signal(signal.SIGINT)
        else:
            os.kill(os.getpid(), signal.SIGINT)
    except Exception:
        import _thread
        _thread.interrupt_main()


def _shm_wait_for(rid, timeout=10.0):
    deadline = time.monotonic() + timeout
    while True:
        if rid in _shm_replies:
            return _shm_replies.pop(rid)
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("Sphere did not answer shared-memory request %s" % rid)
        _shm_event.wait(remaining)
        _shm_event.clear()


def _install_shm():
    if not os.environ.get("SPHERE_SHM_PATH"):
        return
    try:
        import sphere_shm
    except ImportError:
        return
    sphere_shm.set_broker_factory(lambda: sphere_shm.KernelBroker(_send, _shm_wait_for))
    import builtins
    builtins.sphere_shm = sphere_shm


def _read(commands):
    for line in commands:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except ValueError:
            continue
        op = message.get("op")
        if op == "interrupt":
            _interrupt()
        elif op == "shm_reply":
            _shm_replies[message.get("rid")] = message
            _shm_event.set()
    # The pipe closed: Sphere is gone, and nobody will ever read this kernel's
    # outputs. Leave rather than hold the memory of a dead notebook.
    time.sleep(1.0)
    os._exit(0)


def main():
    global _EVENTS
    commands, _EVENTS = _channel()
    # As "python -m ipykernel_launcher" would have it: the notebook's folder
    # first on the path, so its own modules import; Sphere's helpers last, so
    # they never shadow one of the user's.
    sys.path[0] = os.getcwd()
    sys.path.append(_HERE)
    _install_shm()
    threading.Thread(target=_read, args=(commands,), name="sphere-side-channel",
                     daemon=True).start()
    from ipykernel import kernelapp
    kernelapp.launch_new_instance()


if __name__ == "__main__":
    main()
""";

    private static final String TOOLS_SOURCE = """
# Sphere's namespace helpers, imported by both of its notebook kernels (the
# stdio driver and an ipykernel started through Sphere's bootstrap).
#
# Nothing here runs unless the notebook asks, and nothing is left behind in the
# user's namespace: an ipykernel calls these as silent user expressions, the
# driver from its command thread.
#
# Regenerated by Sphere when missing; edit only if you know what you are doing.
SPHERE_TOOLS_VERSION = @SPHERE_TOOLS_VERSION@

import builtins
import json
import re
import symtable
import types

# Names a module scope has without any cell defining them.
_MODULE_NAMES = {"__name__", "__doc__", "__file__", "__builtins__", "__spec__",
                 "__loader__", "__package__", "__annotations__", "__dict__"}
# What IPython puts in the namespace of every session, kept out of the inspector.
_IPYTHON_NAMES = {"In", "Out", "exit", "quit", "get_ipython", "display"}


class _Raw:
    \"\"\"A value whose text/plain is the JSON itself.

    User expressions go through IPython's display formatter, which would quote
    a str and escape it; this prints exactly what it holds.
    \"\"\"

    __slots__ = ("text",)

    def __init__(self, text):
        self.text = text

    def __repr__(self):
        return self.text

    def _repr_pretty_(self, printer, cycle):
        printer.text(self.text)


def _shell(ns):
    getter = ns.get("get_ipython")
    if callable(getter):
        try:
            return getter()
        except Exception:
            return None
    return None


def _kind(value):
    \"\"\"What a live name is, as the highlighter colors it.\"\"\"
    if isinstance(value, types.ModuleType):
        return "module"
    if isinstance(value, type):
        return "class"
    if isinstance(value, (types.FunctionType, types.BuiltinFunctionType, types.MethodType,
                          types.BuiltinMethodType, types.LambdaType)):
        return "function"
    if callable(value) and type(value).__module__ in ("functools", "builtins"):
        return "function"
    return "data"


def _describe(value):
    \"\"\"A short type and value for the inspector, without importing numpy or pandas.\"\"\"
    kind = type(value).__name__
    try:
        shape = getattr(value, "shape", None)
        if shape is not None and not callable(shape):
            dtype = getattr(getattr(value, "dtype", None), "name", None) or str(getattr(value, "dtype", ""))
            return kind, "%s %s" % (dtype, tuple(shape))
        if isinstance(value, (list, tuple, set, frozenset, dict)):
            return kind, "%d elements" % len(value)
        text = repr(value)
        return kind, text if len(text) <= 80 else text[:77] + "..."
    except Exception:
        return kind, ""


def _family(value):
    # The family of a live value, the color code the notebook gives its name: what it is at run time.
    t = type(value)
    mod = t.__module__ or ""
    name = t.__name__
    root = mod.split(".")[0]
    kind = _kind(value)
    if kind != "data":
        return kind
    if value is None or isinstance(value, (bool, int, float, complex)):
        return "scalar"
    if isinstance(value, (str, bytes, bytearray)):
        return "text"
    mro = set()
    for c in getattr(t, "__mro__", ()):
        mro.add((getattr(c, "__module__", "") or "").split(".")[0] + "." + c.__name__)
    if root in ("torch", "tensorflow", "jax", "jaxlib", "keras") and (
            "Tensor" in name or "Variable" in name or name in ("Parameter", "Array", "ArrayImpl")):
        return "tensor"
    if ("torch.Module" in mro or "sklearn.BaseEstimator" in mro or root in ("xgboost", "lightgbm")
            or any(m.split(".")[0] in ("keras", "tf_keras", "tensorflow") and m.split(".")[1] in ("Model", "Layer")
                   for m in mro)):
        return "model"
    if root == "numpy":
        return "array" if name in ("ndarray", "matrix", "memmap", "MaskedArray", "recarray") else "scalar"
    if root in ("pandas", "polars", "pyarrow"):
        return "frame"
    if root == "awkward":
        return "jagged"
    rootish = root in ("cppyy", "ROOT") or "ROOT" in mod
    if (root in ("hist", "boost_histogram") or rootish and name.startswith(("TH1", "TH2", "TH3", "TProfile"))
            or root == "uproot" and "_TH" in name):
        return "hist"
    if root == "uproot" or rootish:
        return "root"
    if root == "matplotlib" and name in ("Figure", "SubFigure", "Axes", "AxesSubplot"):
        return "figure"
    if isinstance(value, (list, tuple, dict, set, frozenset)) or root == "collections":
        return "collection"
    return "object"


def _details(value):
    # A type and a one-line summary of a live value, for the notebook's tooltips and value lens.
    t = type(value)
    label = t.__name__
    root = (t.__module__ or "").split(".")[0]
    try:
        if isinstance(value, types.ModuleType):
            return value.__name__, str(getattr(value, "__version__", "") or "")
        if isinstance(value, type):
            return "class", "%s.%s" % (value.__module__, value.__qualname__)
        if _kind(value) == "function":
            import inspect
            try:
                return "function", label + str(inspect.signature(value))[:70]
            except (TypeError, ValueError):
                return "function", label
        if root == "torch" and hasattr(value, "shape") and hasattr(value, "device"):
            return label, "%s %s %s" % (str(value.dtype).replace("torch.", ""), tuple(value.shape), value.device)
        if root == "awkward":
            return "ak." + label, str(value.type)[:70]
        if root in ("pandas", "polars") and hasattr(value, "shape"):
            shape = tuple(value.shape)
            return label, "%d rows x %d columns" % shape if len(shape) == 2 else "%d rows" % shape[0]
        shape = getattr(value, "shape", None)
        if shape is not None and not callable(shape):
            dtype = getattr(getattr(value, "dtype", None), "name", None) or str(getattr(value, "dtype", ""))
            return label, "%s %s" % (dtype, tuple(shape))
        if root == "torch" and callable(getattr(value, "parameters", None)):
            return label, "{:,} parameters".format(sum(p.numel() for p in value.parameters()))
        if callable(getattr(value, "count_params", None)):
            return label, "{:,} parameters".format(value.count_params())
        if root in ("cppyy", "ROOT") and hasattr(value, "GetEntries") and hasattr(value, "GetName"):
            return value.ClassName(), "'%s', %d entries" % (value.GetName(), value.GetEntries())
        if root == "uproot" and hasattr(value, "num_entries"):
            return label, "%d entries" % value.num_entries
        if isinstance(value, (list, tuple, set, frozenset, dict)):
            return label, "%d elements" % len(value)
        text = repr(value)
        return label, text if len(text) <= 60 else text[:57] + "..."
    except Exception:
        return label, ""


_PEEK_SAMPLE = 2000000
_PEEK_BINS = 28


def _finite(x):
    # A float JSON can carry: NaN and infinities become None.
    try:
        x = float(x)
    except (TypeError, ValueError):
        return None
    return x if x == x and x not in (float("inf"), float("-inf")) else None


def _rebin(counts, bins=_PEEK_BINS):
    counts = [float(c) for c in counts]
    n = len(counts)
    if n <= bins:
        return counts
    step = n / float(bins)
    return [sum(counts[int(i * step):int((i + 1) * step)]) for i in range(bins)]


def _histogram(value):
    # A histogram object's own bins: (contents, low, high, entries, mean, std), or None.
    t = type(value)
    root = (t.__module__ or "").split(".")[0]
    if root in ("cppyy", "ROOT") and hasattr(value, "GetNbinsX") and hasattr(value, "GetBinContent"):
        n = value.GetNbinsX()
        axis = value.GetXaxis()
        return ([value.GetBinContent(i) for i in range(1, n + 1)], axis.GetXmin(), axis.GetXmax(),
                value.GetEntries(), value.GetMean(), value.GetStdDev())
    if root in ("hist", "boost_histogram") and hasattr(value, "values") and len(getattr(value, "axes", ())) == 1:
        values = list(value.values())
        edges = value.axes[0].edges
        return values, edges[0], edges[-1], sum(values), None, None
    if root == "uproot" and hasattr(value, "to_numpy") and "_TH1" in t.__name__:
        values, edges = value.to_numpy()
        return list(values), edges[0], edges[-1], sum(values), None, None
    return None


def _numbers(value):
    # The numbers a value holds as a flat float64 NumPy array, sampled when large, with their count; or None.
    import numpy as np
    t = type(value)
    root = (t.__module__ or "").split(".")[0]
    if root == "torch" and hasattr(value, "detach"):
        v = value.detach()
        if v.is_complex():
            v = v.abs()
        v = v.reshape(-1)
        n = v.numel()
        if n > _PEEK_SAMPLE:
            v = v[::max(1, n // _PEEK_SAMPLE)]
        return v.to("cpu").double().numpy(), n
    if root == "awkward":
        import awkward as ak
        arr = np.asarray(ak.to_numpy(ak.flatten(value, axis=None)))
    elif root in ("tensorflow", "jax", "jaxlib", "keras"):
        arr = np.asarray(value)
    elif root in ("pandas", "polars") and hasattr(value, "to_numpy") and getattr(value, "ndim", 2) == 1:
        arr = np.asarray(value.to_numpy())
    elif isinstance(value, np.ndarray) or root == "numpy":
        arr = np.asarray(value)
    elif (isinstance(value, (list, tuple)) and 0 < len(value) <= 1000000
          and all(isinstance(x, (int, float)) and not isinstance(x, bool) for x in value[:200])):
        arr = np.asarray(value, dtype=float)
    else:
        return None
    if arr.dtype.kind == "c":
        arr = np.abs(arr)
    if arr.dtype.kind not in "biuf":
        return None
    n = arr.size
    flat = arr.reshape(-1)
    if n > _PEEK_SAMPLE:
        flat = flat[::max(1, n // _PEEK_SAMPLE)]
    return flat.astype(np.float64, copy=False), n


def _peek(value):
    t = type(value)
    root = (t.__module__ or "").split(".")[0]
    h = _histogram(value)
    if h is not None:
        contents, lo, hi, entries, mean, std = h
        return {"kind": "numbers", "hist": _rebin(contents), "lo": _finite(lo), "hi": _finite(hi),
                "n": _finite(entries), "mean": _finite(mean), "std": _finite(std), "histogram": True}
    if root in ("pandas", "polars") and hasattr(value, "columns"):
        columns = [[str(c), str(d)] for c, d in list(zip(value.columns, value.dtypes))[:40]]
        if root == "pandas":
            head = value.head(5).to_string(max_cols=8, max_colwidth=14)
        else:
            head = str(value.head(5))
        return {"kind": "frame", "rows": len(value), "columns": columns, "head": head[:1500]}
    if root == "uproot" and hasattr(value, "num_entries") and hasattr(value, "keys"):
        return {"kind": "tree", "entries": value.num_entries, "branches": [str(k) for k in value.keys()][:40]}
    if root in ("cppyy", "ROOT") and hasattr(value, "GetListOfBranches"):
        return {"kind": "tree", "entries": value.GetEntries(),
                "branches": [b.GetName() for b in value.GetListOfBranches()][:40]}
    if root == "awkward" and getattr(value, "fields", None):
        return {"kind": "mapping", "n": len(value.fields), "keys": list(value.fields)[:30]}
    got = _numbers(value)
    if got is not None:
        import numpy as np
        arr, total = got
        out = {"kind": "numbers", "n": int(total), "sampled": bool(arr.size < total)}
        finite = np.isfinite(arr)
        nf = int(finite.sum())
        out["nan"] = int(np.isnan(arr).sum())
        out["inf"] = int(arr.size - nf - out["nan"])
        if nf:
            f = arr[finite]
            lo, hi = float(f.min()), float(f.max())
            out.update({"min": lo, "max": hi, "mean": float(f.mean()), "std": float(f.std()), "lo": lo, "hi": hi})
            if hi > lo:
                counts, _edges = np.histogram(f, bins=_PEEK_BINS, range=(lo, hi))
                out["hist"] = [int(c) for c in counts]
            else:
                out["hist"] = [nf]
        if root == "awkward":
            try:
                import awkward as ak
                if value.ndim >= 2:
                    per = np.asarray(ak.num(value, axis=1))
                    out["per_entry"] = {"mean": float(per.mean()) if per.size else 0.0,
                                        "max": int(per.max()) if per.size else 0,
                                        "empty": int((per == 0).sum())}
            except Exception:
                pass
        return out
    if isinstance(value, dict):
        return {"kind": "mapping", "n": len(value), "keys": [repr(k)[:30] for k in list(value)[:20]]}
    text = repr(value)
    return {"kind": "repr", "text": text[:600]}


def peek(name, ns):
    # What a live value looks like, for the notebook's tooltips: the distribution of its numbers
    # (a histogram's own bins), a frame's columns and first rows, a tree's branches, a mapping's keys.
    if name not in ns:
        out = {"kind": "error", "text": "%s is not defined in the kernel" % name}
    else:
        try:
            out = _peek(ns[name])
        except Exception as e:
            out = {"kind": "error", "text": "%s: %s" % (type(e).__name__, e)}
    out["name"] = name
    return _Raw(json.dumps([out], default=str))


def variables(ns):
    \"\"\"The user's variables, sorted, as the Variables tab lists them.\"\"\"
    shell = _shell(ns)
    hidden = set(getattr(shell, "user_ns_hidden", {}) or {}) if shell is not None else set()
    items = []
    for name, value in list(ns.items()):
        if name.startswith("_") or name in hidden or name in _IPYTHON_NAMES:
            continue
        if isinstance(value, (types.ModuleType, type, types.FunctionType, types.BuiltinFunctionType)):
            continue
        kind, info = _describe(value)
        items.append({"name": name, "type": kind, "info": info})
    items.sort(key=lambda item: item["name"])
    return _Raw(json.dumps(items))


def _without_magics(code):
    # The stdio driver has no IPython: a magic or shell line is not Python, and
    # would make the whole cell a syntax error to symtable.
    lines = code.split("\\n")
    if lines and lines[0].lstrip().startswith("%%"):
        return ""
    out = []
    for line in lines:
        stripped = line.lstrip()
        if stripped.startswith(("%", "!")) or stripped.endswith("?") and not stripped.startswith("#"):
            out.append(line[:len(line) - len(stripped)] + "pass")
            continue
        # files = !ls and t = %timeit -o f(): the name is bound, the rest is IPython's
        assigned = _MAGIC_ASSIGNMENT.match(line)
        out.append(assigned.group(1) + "None" if assigned else line)
    return "\\n".join(out)


_MAGIC_ASSIGNMENT = re.compile(r"^(\\s*[A-Za-z_][\\w.]*(?:\\s*,\\s*[A-Za-z_][\\w.]*)*\\s*=\\s*)[!%]")


# Scopes whose body runs as soon as the cell reaches them; any other function
# body runs only when called, and an annotation is evaluated lazily (PEP 649).
_RUN_AT_ONCE = {"<genexpr>", "<listcomp>", "<setcomp>", "<dictcomp>"}


def _walk(table, mode, bound, now, deferred):
    \"\"\"Collects what a cell binds, and what it reads now or only when called.

    mode is "top" for the cell itself, "now" for a class body or a generator
    expression reached from it, "deferred" inside any function body.
    \"\"\"
    for symbol in table.get_symbols():
        name = symbol.get_name()
        # Python 3.12 inlines comprehensions into the enclosing table; their loop
        # variable still does not outlive them.
        comprehension = getattr(symbol, "is_comp_iter", None)
        if comprehension is not None and comprehension():
            continue
        if mode == "top":
            if symbol.is_assigned() or symbol.is_imported() or symbol.is_namespace():
                bound.add(name)
            if symbol.is_referenced():
                now.add(name)
        elif symbol.is_declared_global() and symbol.is_assigned():
            bound.add(name)
        elif symbol.is_global() and symbol.is_referenced():
            (now if mode == "now" else deferred).add(name)
    for child in table.get_children():
        kind = str(child.get_type()).lower()
        immediate = mode != "deferred" and (kind.endswith("class") or child.get_name() in _RUN_AT_ONCE)
        _walk(child, "now" if immediate else "deferred", bound, now, deferred)


# A star import or a %run defines names nobody can list without running it.
_OPAQUE = re.compile(r"^[ \\t]*(from[ \\t]+[\\w.]+[ \\t]+import[ \\t]+\\*|%run\\b)", re.M)


def _parse(code, shell):
    \"\"\"(bound, used now, used when called) of one cell, or the SyntaxError that stops it.\"\"\"
    if shell is not None:
        try:
            source = shell.transform_cell(code)
        except Exception:
            source = _without_magics(code)
    else:
        source = _without_magics(code)
    try:
        table = symtable.symtable(source, "<cell>", "exec")
    except (SyntaxError, ValueError) as error:
        return error
    bound, now, deferred = set(), set(), set()
    _walk(table, "top", bound, now, deferred)
    return bound, now, deferred


def analyze(cells, ns):
    \"\"\"For each cell, in notebook order, what it defines, uses, and gets wrong.

    Only what the cell reads as it runs can fail as it runs: a name read inside
    a function body is looked up when the function is called, usually after the
    cell that creates it, so it is checked only for existing somewhere.

    undefined -- names read now that no cell, no live variable and no builtin
                 provides: a NameError whatever the order the cells run in.
    latent    -- the same, read inside a function: it fails when called.
    pending   -- {name: cell} read now, defined by a cell above that has not run.
    later     -- {name: cell} read now, defined only by a cell below: run top to
                 bottom, as Restart & Run All does, this cell stops there.
    order     -- {name: cell or -1} read now and held by the kernel although no
                 cell above defines it: the result depends on the order the
                 cells were run in, or on a cell since deleted (-1).
    kinds     -- the live kind (module, class, function, data) of each used name
                 the kernel holds, for the highlighter.
    types     -- {name: {f: family, t: type, i: summary, c: cell}} of the same
                 names: the runtime family the color code shows (tensor, array,
                 frame, jagged, hist, root, model...), and, from Sphere's own
                 kernel, the execution count of the cell that made the value.
    stale     -- {name: why} the names whose value was computed from one that
                 has changed since (Sphere's own kernel tracks provenance).
    \"\"\"
    shell = _shell(ns)
    tracker = getattr(builtins, "__sphere_kernel__", None)
    stale_all = {}
    made = {}
    if tracker is not None:
        try:
            stale_all = tracker.provenance.stale(ns)
            made = dict(tracker.provenance.cell)
        except Exception:
            stale_all, made = {}, {}
    extra = _MODULE_NAMES | (_IPYTHON_NAMES if shell is not None else set())
    parsed = [_parse(code, shell) for code in cells]
    opaque = [bool(_OPAQUE.search(code)) for code in cells]
    defined_in = {}
    for index, result in enumerate(parsed):
        if isinstance(result, tuple):
            for name in result[0]:
                defined_in.setdefault(name, []).append(index)
    out = []
    for index, result in enumerate(parsed):
        if not isinstance(result, tuple):
            error = result
            out.append({"syntax": [getattr(error, "lineno", 0) or 0,
                                   getattr(error, "offset", 0) or 0,
                                   str(getattr(error, "msg", error))],
                        "undefined": [], "latent": [], "pending": {}, "later": {}, "order": {},
                        "kinds": {}, "types": {}, "stale": {}, "defs": [], "uses": []})
            continue
        bound, now, deferred = result
        undefined, latent, pending, later, order = [], [], {}, {}, {}
        anywhere_opaque = any(opaque)
        for name in sorted((now | deferred) - bound):
            if hasattr(builtins, name) or name in extra:
                continue
            cells_defining = defined_in.get(name, [])
            if name not in now:
                # read only when a function runs: wrong only if nothing defines it at all
                if name not in ns and not cells_defining and not anywhere_opaque:
                    latent.append(name)
                continue
            above = [i for i in cells_defining if i < index]
            below = [i for i in cells_defining if i > index]
            if name in ns:
                if not above:
                    order[name] = below[0] if below else -1
            elif above:
                pending[name] = above[-1]
            elif any(opaque[:index + 1]):
                continue  # may come from the star import or the %run above
            elif below:
                later[name] = below[0]
            else:
                undefined.append(name)
        used = now | deferred
        # what the cell reads and what it binds: a def or an assignment is colored too
        kinds = {n: _kind(ns[n]) for n in used | bound if n in ns and n not in extra}
        found = {}
        for n in kinds:
            label, summary = _details(ns[n])
            entry = {"f": _family(ns[n]), "t": label, "i": summary}
            if n in made:
                entry["c"] = made[n]
            found[n] = entry
        stale = {n: stale_all[n] for n in kinds if n in stale_all}
        out.append({"undefined": undefined, "latent": latent, "pending": pending, "later": later,
                    "order": order, "kinds": kinds, "types": found, "stale": stale,
                    "defs": sorted(bound), "uses": sorted(used)})
    return _Raw(json.dumps(out))
""";
}
