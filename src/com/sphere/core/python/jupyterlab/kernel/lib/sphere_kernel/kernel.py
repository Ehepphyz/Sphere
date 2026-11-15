"""Sphere's homemade Jupyter kernel.

Threads:
    main            runs the cells, one shell request after the other, so that
                    signals, matplotlib and every library find the thread they
                    expect;
    sphere-kernel-io     every socket (zmtp.Loop); it answers the heartbeat
                    itself, so a long cell never looks like a dead kernel;
    control         interrupt, shutdown, kernel_info and usage, answered even
                    while a cell runs;
    flusher         sends what the streams buffered, every 50 ms.

An interrupt arrives as a message (interrupt_mode "message"), which works on
Windows too, where a signal-mode kernel needs an event handle from its parent:
the control thread raises SIGINT in the process, which also wakes a
time.sleep, and the cell gets a KeyboardInterrupt.
"""

import ast
import builtins
import codecs
import inspect
import io
import json
import linecache
import os
import platform
import queue
import re
import signal
import socket
import sys
import threading
import time
import traceback
import types
import uuid

from . import __version__
from . import completion as _completion
from . import display as _display
from . import magics as _magics
from . import provenance as _provenance
from . import session as _session
from . import transform as _transform
from . import zmtp

RED = "\x1b[0;31m"
GREEN = "\x1b[0;32m"
CYAN = "\x1b[0;36m"
BOLD_RED = "\x1b[1;31m"
DIM = "\x1b[2m"
RESET = "\x1b[0m"

_PACKAGE_DIR = os.path.dirname(os.path.abspath(__file__))
_CELL_FILE = re.compile(r"^<Cell In\[(\d+)\]>$")


class StdinNotImplementedError(RuntimeError):
    """input() in a request that did not allow stdin."""


class OutStream(io.TextIOBase):
    """sys.stdout and sys.stderr: what is written becomes stream messages of the running cell."""

    def __init__(self, kernel, name, fd):
        super().__init__()
        self.kernel = kernel
        self.name = name
        self._fd = fd
        self._parts = []
        self._size = 0
        self._lock = threading.Lock()

    @property
    def encoding(self):
        return "utf-8"

    @property
    def errors(self):
        return "replace"

    def isatty(self):
        return False

    def readable(self):
        return False

    def writable(self):
        return True

    def fileno(self):
        return self._fd

    def write(self, s):
        if not isinstance(s, str):
            raise TypeError("write() argument must be str, not %s" % type(s).__name__)
        if not s:
            return 0
        with self._lock:
            self._parts.append(s)
            self._size += len(s)
            big = self._size > 65536
        if big:
            self.flush()
        return len(s)

    def writelines(self, lines):
        for line in lines:
            self.write(line)

    def flush(self):
        with self._lock:
            if not self._parts:
                return
            text = "".join(self._parts)
            self._parts = []
            self._size = 0
        self.kernel.publish_stream(self.name, text)

    @property
    def buffer(self):
        return _BinaryWriter(self)


class _BinaryWriter:
    def __init__(self, stream):
        self._stream = stream
        self._decoder = codecs.getincrementaldecoder("utf-8")("replace")

    def write(self, b):
        self._stream.write(self._decoder.decode(bytes(b)))
        return len(b)

    def flush(self):
        self._stream.flush()


class _Events:
    """get_ipython().events: pre/post cell hooks some libraries register."""

    def __init__(self):
        self.callbacks = {"pre_execute": [], "pre_run_cell": [], "post_execute": [], "post_run_cell": [],
                          "shell_initialized": []}

    def register(self, event, fn):
        self.callbacks.setdefault(event, []).append(fn)

    def unregister(self, event, fn):
        try:
            self.callbacks.get(event, []).remove(fn)
        except ValueError:
            pass

    def trigger(self, event, *args):
        for fn in list(self.callbacks.get(event, [])):
            try:
                fn(*args)
            except TypeError:
                try:
                    fn()
                except Exception:
                    traceback.print_exc()
            except Exception:
                traceback.print_exc()


class _Shell:
    """What get_ipython() returns in Sphere's kernel: the methods transformed cells call, and a few more."""

    def __init__(self, kernel):
        self.kernel = kernel
        self.events = _Events()
        self.config = {}

    def __repr__(self):
        return "<SphereShell of the Sphere HEP kernel %s>" % __version__

    @property
    def user_ns(self):
        return self.kernel.user_ns

    @property
    def user_ns_hidden(self):
        return self.kernel.hidden

    @property
    def execution_count(self):
        return self.kernel.execution_count

    def run_line_magic(self, magic_name, line, _stack_depth=1):
        return _magics.run_line(self.kernel, magic_name, line)

    def run_cell_magic(self, magic_name, line, cell):
        return _magics.run_cell(self.kernel, magic_name, line, cell)

    def magic(self, line):
        name, _, rest = line.lstrip("%").partition(" ")
        return self.run_line_magic(name, rest)

    def system(self, cmd):
        return _magics.system(self.kernel, cmd)

    def getoutput(self, cmd, split=True, depth=0):
        out = _magics.getoutput(self.kernel, cmd)
        return out if split else out.n

    def transform_cell(self, raw_cell):
        return _transform.transform_cell(raw_cell)

    def inspect_object(self, expr, detail_level=0):
        text = _completion.documentation(expr, self.user_ns, detail_level)
        if text is None:
            print("Object `%s` not found." % expr)
        else:
            self.kernel.payloads.append({"source": "page", "data": {"text/plain": text}, "start": 0})

    def ev(self, expr):
        return eval(expr, self.user_ns)

    def ex(self, cmd):
        exec(cmd, self.user_ns)

    def push(self, variables, interactive=True):
        if isinstance(variables, str):
            frame = sys._getframe(1)
            variables = {name: eval(name, frame.f_globals, frame.f_locals) for name in variables.split()}
        self.user_ns.update(variables)

    def set_next_input(self, text, replace=False):
        self.kernel.payloads.append({"source": "set_next_input", "text": text, "replace": replace})

    def register_magic_function(self, func, magic_kind="line", magic_name=None):
        name = magic_name or func.__name__
        if magic_kind in ("line", "line_cell"):
            self.kernel.custom_line[name] = func
        if magic_kind in ("cell", "line_cell"):
            self.kernel.custom_cell[name] = func

    def run_cell(self, raw_cell, store_history=False, silent=False, shell_futures=True):
        return self.kernel.run_body(raw_cell, display_last=not silent)

    def enable_matplotlib(self, gui=None):
        _magics.run_line(self.kernel, "matplotlib", gui or "inline")

    def showtraceback(self, *args, **kwargs):
        traceback.print_exc()

    def object_inspect_text(self, oname, detail_level=0):
        return _completion.documentation(oname, self.user_ns, detail_level) or ""


class _Exit:
    def __repr__(self):
        return "Use the kernel menu (Restart or Shut down) to stop Sphere's kernel."

    def __call__(self, *args, **kwargs):
        print(repr(self))


class _PostImport:
    """Calls a function on a module right after its first import (to patch IPython's display())."""

    def __init__(self, hooks):
        self.hooks = hooks
        self._busy = set()

    def find_spec(self, fullname, path=None, target=None):
        if fullname not in self.hooks or fullname in self._busy:
            return None
        import importlib.util
        self._busy.add(fullname)
        try:
            spec = importlib.util.find_spec(fullname)
        except Exception:
            return None
        finally:
            self._busy.discard(fullname)
        if spec is None or spec.loader is None or not hasattr(spec.loader, "exec_module"):
            return spec
        spec.loader = _HookLoader(spec.loader, self.hooks[fullname])
        return spec

    def invalidate_caches(self):
        pass


class _HookLoader:
    def __init__(self, loader, hook):
        self.loader = loader
        self.hook = hook

    def create_module(self, spec):
        return self.loader.create_module(spec)

    def exec_module(self, module):
        self.loader.exec_module(module)
        try:
            self.hook(module)
        except Exception:
            pass

    def __getattr__(self, name):
        return getattr(self.loader, name)


class Kernel:

    def __init__(self, connection):
        if connection.get("transport", "tcp") != "tcp":
            raise SystemExit("Sphere's kernel speaks TCP only, not %s." % connection.get("transport"))
        self.connection = connection
        self.session = _session.Session(connection.get("key", ""), connection.get("signature_scheme", "hmac-sha256"))
        self.ident = str(uuid.uuid4())
        self.loop = zmtp.Loop()
        ip = connection.get("ip", "127.0.0.1")
        self.shell_queue = queue.Queue()
        self.control_queue = queue.Queue()
        self.input_replies = queue.Queue()
        self.shell_socket = zmtp.Router(self.loop, ip, int(connection["shell_port"]), self.shell_queue.put)
        self.control_socket = zmtp.Router(self.loop, ip, int(connection["control_port"]), self.control_queue.put)
        self.stdin_socket = zmtp.Router(self.loop, ip, int(connection["stdin_port"]), self._on_stdin)
        self.iopub_socket = zmtp.Pub(self.loop, ip, int(connection["iopub_port"]))
        self.hb_socket = zmtp.Rep(self.loop, ip, int(connection["hb_port"]))

        self.execution_count = 0
        self.exec_parent = {}
        self.exec_idents = []
        self.allow_stdin = False
        self.payloads = []
        self.history = []
        self.custom_line = {}
        self.custom_cell = {}
        self.provenance = _provenance.Provenance()
        self.executing = False
        self.capturing_fds = False
        self.shutting_down = False
        self.shell = _Shell(self)
        self.user_ns = {}
        self.hidden = {}
        self._box = None
        self._async_loop = None
        self._main_ident = threading.get_ident()
        self._side_out = None
        self._side_lock = threading.Lock()
        self._shm_replies = {}
        self._shm_event = threading.Event()
        self._log_fd = None
        self._fd_sync = {}
        self.stdout = OutStream(self, "stdout", 1)
        self.stderr = OutStream(self, "stderr", 2)
        self._init_namespace()

    # ---- Namespace -------------------------------------------------------------------

    def _init_namespace(self):
        self.user_ns.clear()
        self.user_ns.update({
            "__name__": "__main__", "__builtins__": builtins, "__doc__": None, "__package__": None,
            "__spec__": None, "__loader__": None,
            "In": [""], "Out": {}, "get_ipython": lambda: self.shell, "display": _display.display,
            "exit": _Exit(), "quit": _Exit(),
        })
        self.hidden = dict(self.user_ns)
        self.user_ns.update({"_": "", "__": "", "___": "", "_i": "", "_ii": "", "_iii": ""})

    def reset_namespace(self):
        self._init_namespace()
        self.provenance.forget()

    # ---- Starting --------------------------------------------------------------------

    def start(self):
        self._side_channel()
        try:
            self._log_fd = os.dup(2)
        except OSError:
            self._log_fd = None
        _display.KERNEL = self
        builtins.__sphere_kernel__ = self
        self._install_import_hooks()
        backend = os.environ.get("MPLBACKEND", "")
        if not backend or backend == "module://sphere_mpl":
            os.environ["MPLBACKEND"] = _magics.INLINE
        self.loop.start()
        self._capture_fds()
        sys.stdout = self.stdout
        sys.stderr = self.stderr
        builtins.input = self._input
        try:
            import getpass
            getpass.getpass = lambda prompt="Password: ", stream=None: self._input(prompt, password=True)
        except Exception:
            pass
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        for target in (self._control_thread, self._flusher):
            threading.Thread(target=target, name="sphere-kernel-" + target.__name__.strip("_"), daemon=True).start()
        self._parent_poller()
        self.serve()

    def log(self, message):
        if self._log_fd is not None and os.environ.get("SPHERE_KERNEL_DEBUG"):
            try:
                os.write(self._log_fd, ("[sphere-kernel] %s\n" % message).encode("utf-8", "replace"))
            except OSError:
                pass

    def _install_import_hooks(self):
        def patch(module):
            for name, fn in (("display", _display.display), ("clear_output", _display.clear_output),
                             ("update_display", _display.update_display),
                             ("publish_display_data", _display.publish_display_data)):
                if hasattr(module, name):
                    setattr(module, name, fn)

        targets = ("IPython.core.display_functions", "IPython.core.display", "IPython.display")
        for name in targets:
            if name in sys.modules:
                patch(sys.modules[name])
        sys.meta_path.insert(0, _PostImport({name: patch for name in targets}))

    def _capture_fds(self):
        """C and C++ code writes to file descriptors 1 and 2, not to sys.stdout: those go to the cell too."""
        default = "0" if self._side_out is not None else "1"
        if os.environ.get("SPHERE_KERNEL_CAPTURE_FD", default) != "1":
            return
        for fd, stream in ((1, self.stdout), (2, self.stderr)):
            try:
                r, w = _big_pipe()
                os.dup2(w, fd)
                os.close(w)
            except Exception:
                continue
            event = threading.Event()
            self._fd_sync[fd] = event
            threading.Thread(target=self._pump_fd, args=(r, stream, event), name="sphere-kernel-fd%d" % fd,
                             daemon=True).start()
            self.capturing_fds = True

    _SYNC = b"\x00\x1bsphere-sync\x1b\x00"

    def _pump_fd(self, r, stream, event):
        decoder = codecs.getincrementaldecoder("utf-8")("replace")
        pending = b""
        while True:
            try:
                data = os.read(r, 65536)
            except OSError:
                return
            if not data:
                return
            data = pending + data
            pending = b""
            synced = self._SYNC in data
            if synced:
                data = data.replace(self._SYNC, b"")
            elif data.endswith(self._SYNC[:1]) or any(data.endswith(self._SYNC[:k]) for k in range(2, len(self._SYNC))):
                cut = max(k for k in range(1, len(self._SYNC)) if data.endswith(self._SYNC[:k]))
                data, pending = data[:-cut], data[-cut:]
            text = decoder.decode(data)
            if text:
                stream.write(text)
            if synced:
                event.set()

    def sync_fds(self, timeout=0.5):
        """What C code wrote before the cell ended reaches the cell, not the next one."""
        for fd, event in self._fd_sync.items():
            event.clear()
            try:
                os.write(fd, self._SYNC)
            except OSError:
                continue
            event.wait(timeout)

    # ---- Sphere's side channel (only when Sphere started the kernel) --------------------

    def _side_channel(self):
        if os.environ.get("SPHERE_SIDE_CHANNEL") != "1":
            return
        try:
            commands = os.dup(0)
            events = os.dup(1)
            null = os.open(os.devnull, os.O_RDONLY)
            os.dup2(null, 0)
            os.close(null)
            sys.stdin = open(0, "r", closefd=False)
        except OSError:
            return
        self._side_out = open(events, "w", encoding="utf-8")
        reader = open(commands, "r", encoding="utf-8", errors="replace")
        threading.Thread(target=self._read_side, args=(reader,), name="sphere-side-channel", daemon=True).start()
        if os.environ.get("SPHERE_SHM_PATH"):
            try:
                import sphere_shm
                sphere_shm.set_broker_factory(lambda: sphere_shm.KernelBroker(self._side_send, self._shm_wait_for))
                builtins.sphere_shm = sphere_shm
            except Exception:
                pass

    def _side_send(self, message):
        with self._side_lock:
            self._side_out.write(json.dumps(message) + "\n")
            self._side_out.flush()

    def _shm_wait_for(self, rid, timeout=10.0):
        deadline = time.monotonic() + timeout
        while True:
            if rid in self._shm_replies:
                return self._shm_replies.pop(rid)
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("Sphere did not answer shared-memory request %s" % rid)
            self._shm_event.wait(remaining)
            self._shm_event.clear()

    def _read_side(self, commands):
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
                self.interrupt()
            elif op == "shm_reply":
                self._shm_replies[message.get("rid")] = message
                self._shm_event.set()
        # The pipe closed: Sphere is gone and nobody will read this kernel again.
        time.sleep(1.0)
        os._exit(0)

    def _parent_poller(self):
        parent = os.environ.get("JPY_PARENT_PID")
        event = os.environ.get("JPY_INTERRUPT_EVENT") or os.environ.get("IPY_INTERRUPT_EVENT")
        if os.name == "nt":
            handles = []
            for value, what in ((parent, "parent"), (event, "interrupt")):
                try:
                    if value and int(value):
                        handles.append((int(value), what))
                except ValueError:
                    pass
            if handles:
                threading.Thread(target=self._poll_windows, args=(handles,), daemon=True).start()
        elif parent:
            def poll():
                while True:
                    time.sleep(1.0)
                    if os.getppid() != int(parent):
                        os._exit(1)
            threading.Thread(target=poll, daemon=True).start()

    def _poll_windows(self, handles):
        try:
            import ctypes
            from ctypes import wintypes
            k32 = ctypes.WinDLL("kernel32", use_last_error=True)
            wait = k32.WaitForMultipleObjects
            wait.argtypes = [wintypes.DWORD, ctypes.POINTER(wintypes.HANDLE), wintypes.BOOL, wintypes.DWORD]
            wait.restype = wintypes.DWORD
            array = (wintypes.HANDLE * len(handles))(*[h for h, _ in handles])
            while True:
                r = wait(len(handles), array, False, 1000)
                if r == 0x102:  # WAIT_TIMEOUT
                    continue
                if r >= len(handles):
                    return  # WAIT_FAILED: the handles were not ours to wait on
                if handles[r][1] == "parent":
                    os._exit(1)
                self.interrupt()
                k32.ResetEvent(wintypes.HANDLE(handles[r][0]))
        except Exception:
            return

    # ---- Publishing -----------------------------------------------------------------

    def publish(self, msg_type, content, parent=None, metadata=None):
        topic = ("kernel.%s.%s" % (self.ident, msg_type)).encode("ascii")
        frames = self.session.serialize(msg_type, content, self.exec_parent if parent is None else parent,
                                        metadata, idents=[topic])
        self.iopub_socket.send(frames)

    def publish_stream(self, name, text):
        box = self._box
        if box is not None:
            box[name].append(text)
            return
        self.publish("stream", {"name": name, "text": text})

    def publish_display(self, data, metadata, transient, update):
        box = self._box
        if box is not None:
            box["outputs"].append((data, metadata))
            return
        self.stdout.flush()
        self.stderr.flush()
        self.publish("update_display_data" if update else "display_data",
                     {"data": data, "metadata": metadata or {}, "transient": transient or {}})

    def publish_clear(self, wait):
        self.stdout.flush()
        self.stderr.flush()
        self.publish("clear_output", {"wait": bool(wait)})

    def capturing(self):
        kernel = self

        class _Capture:
            def __enter__(self):
                kernel.stdout.flush()
                kernel.stderr.flush()
                self.saved = kernel._box
                kernel._box = {"stdout": [], "stderr": [], "outputs": []}
                return kernel._box

            def __exit__(self, *exc):
                kernel.stdout.flush()
                kernel.stderr.flush()
                kernel._box = self.saved
                return False

        return _Capture()

    def reply(self, sock, idents, request, msg_type, content, metadata=None):
        frames = self.session.serialize(msg_type, content, request["header"], metadata, idents=idents[:1] or [b""])
        sock.send(frames)

    def _flusher(self):
        while not self.shutting_down:
            time.sleep(0.05)
            try:
                self.stdout.flush()
                self.stderr.flush()
            except Exception:
                pass

    # ---- The shell channel, on the main thread -------------------------------------------

    def serve(self):
        while not self.shutting_down:
            try:
                frames = self.shell_queue.get(timeout=0.2)
            except queue.Empty:
                continue
            except KeyboardInterrupt:
                continue  # an interrupt between two cells has nothing to stop
            try:
                idents, msg = self.session.deserialize(frames)
            except Exception as e:
                self.log("dropped a shell message: %s" % e)
                continue
            try:
                self.dispatch(idents, msg)
            except KeyboardInterrupt:
                pass

    def dispatch(self, idents, msg):
        msg_type = msg["msg_type"]
        handler = getattr(self, "_on_" + msg_type, None)
        self.publish("status", {"execution_state": "busy"}, parent=msg["header"])
        try:
            if handler is None:
                self.log("no handler for %s" % msg_type)
            else:
                handler(idents, msg)
        except Exception:
            self.log(traceback.format_exc())
        finally:
            self.stdout.flush()
            self.stderr.flush()
            self.publish("status", {"execution_state": "idle"}, parent=msg["header"])

    def kernel_info(self):
        return {
            "status": "ok",
            "protocol_version": _session.PROTOCOL_VERSION,
            "implementation": "sphere_kernel",
            "implementation_version": __version__,
            "language_info": {
                "name": "python",
                "version": platform.python_version(),
                "mimetype": "text/x-python",
                "file_extension": ".py",
                "pygments_lexer": "ipython3",
                "codemirror_mode": {"name": "ipython", "version": 3},
                "nbconvert_exporter": "python",
            },
            "banner": ("Sphere HEP kernel %s: a homemade Jupyter kernel written on the standard library alone.\n"
                       "Python %s\n%%lsmagic lists the magics, obj? documents an object, %%%%cpp runs C++.")
                      % (__version__, sys.version.replace("\n", " ")),
            "help_links": [
                {"text": "Python", "url": "https://docs.python.org/3/"},
                {"text": "NumPy", "url": "https://numpy.org/doc/stable/"},
                {"text": "Awkward Array", "url": "https://awkward-array.org/"},
                {"text": "uproot", "url": "https://uproot.readthedocs.io/"},
                {"text": "ROOT", "url": "https://root.cern/doc/master/"},
            ],
            "debugger": False,
        }

    def _on_kernel_info_request(self, idents, msg):
        self.reply(self.shell_socket, idents, msg, "kernel_info_reply", self.kernel_info())

    def _on_execute_request(self, idents, msg):
        c = msg["content"]
        code = c.get("code", "") or ""
        silent = bool(c.get("silent", False))
        store_history = bool(c.get("store_history", not silent)) and not silent
        user_expressions = c.get("user_expressions") or {}
        stop_on_error = bool(c.get("stop_on_error", True))
        if not silent:
            self.exec_parent = msg["header"]
            self.exec_idents = idents
        self.allow_stdin = bool(c.get("allow_stdin", True))
        self.payloads = []
        if store_history:
            self.execution_count += 1
        count = self.execution_count
        if not silent:
            self.publish("execute_input", {"code": code, "execution_count": count})
        started = _session.now()
        error = None
        if code.strip():
            if store_history:
                self.history.append((1, count, code))
                ns = self.user_ns
                ns["In"].append(code)
                ns["_iii"], ns["_ii"], ns["_i"] = ns.get("_ii", ""), ns.get("_i", ""), code
                ns["_i%d" % count] = code
            error = self.run_cell(code, count, silent=silent)
        content = {"status": "ok" if error is None else "error", "execution_count": count,
                   "user_expressions": self._expressions(user_expressions),
                   "payload": [] if silent else list(self.payloads)}
        if error is not None:
            content.update(error)
        self.stdout.flush()
        self.stderr.flush()
        self.reply(self.shell_socket, idents, msg, "execute_reply", content,
                   {"started": started, "dependencies_met": True, "engine": self.ident, "status": content["status"]})
        if error is not None and stop_on_error and not silent:
            self._abort_queued()

    def _abort_queued(self):
        """After an error, the cells queued behind it (Run All) are not run, as in Jupyter."""
        kept = []
        while True:
            try:
                frames = self.shell_queue.get_nowait()
            except queue.Empty:
                break
            try:
                idents, msg = self.session.deserialize(frames)
            except Exception:
                continue
            if msg["msg_type"] == "execute_request":
                self.publish("status", {"execution_state": "busy"}, parent=msg["header"])
                self.reply(self.shell_socket, idents, msg, "execute_reply",
                           {"status": "aborted", "execution_count": None, "user_expressions": {}, "payload": []},
                           {"status": "aborted", "engine": self.ident})
                self.publish("status", {"execution_state": "idle"}, parent=msg["header"])
            else:
                kept.append(frames)
        for frames in kept:
            self.shell_queue.put(frames)

    def _expressions(self, expressions):
        out = {}
        for key, expr in expressions.items():
            try:
                value = eval(expr, self.user_ns)
                data, md = _display.mimebundle(value)
                out[key] = {"status": "ok", "data": data, "metadata": md}
            except BaseException as e:
                out[key] = {"status": "error", "ename": type(e).__name__, "evalue": str(e), "traceback": []}
        return out

    # ---- Running code ---------------------------------------------------------------

    def run_cell(self, code, count, silent=False):
        """Runs a cell; returns None, or the error content it stopped on."""
        filename = "<Cell In[%d]>" % count
        try:
            source = _transform.transform_cell(code)
        except Exception:
            source = code
        linecache.cache[filename] = (len(code), None, code.splitlines(True) or ["\n"], filename)
        try:
            tree = ast.parse(source, filename=filename, mode="exec")
        except SyntaxError as e:
            return self._error(e, silent, syntax=True)
        display_last = bool(tree.body) and isinstance(tree.body[-1], ast.Expr) and not code.rstrip().endswith(";")
        info = types.SimpleNamespace(raw_cell=code, store_history=not silent, silent=silent, cell_id=None)
        self.shell.events.trigger("pre_execute")
        self.shell.events.trigger("pre_run_cell", info)
        error = None
        result = types.SimpleNamespace(execution_count=count, error_before_exec=None, error_in_exec=None,
                                       info=info, result=None, success=True)
        self.executing = True
        signal.signal(signal.SIGINT, signal.default_int_handler)
        try:
            value = self._run_tree(tree, filename, display_last)
            result.result = value
            if display_last and not silent:
                self.displayhook(value, count)
        except _magics.UsageError as e:
            error = self._usage_error(e, silent)
        except BaseException as e:  # KeyboardInterrupt and SystemExit end the cell, not the kernel
            error = self._error(e, silent)
            result.error_in_exec = e
            result.success = False
        finally:
            signal.signal(signal.SIGINT, signal.SIG_IGN)
            self.executing = False
        if self.capturing_fds and not silent:
            self.sync_fds()
        self.stdout.flush()
        self.stderr.flush()
        if not silent:
            self._flush_figures()
            try:
                self.provenance.record(tree, count, self.user_ns)
            except Exception:
                pass
        self.shell.events.trigger("post_execute")
        self.shell.events.trigger("post_run_cell", result)
        return error

    def run_body(self, code, display_last=False):
        """Runs code in the namespace for a magic (%%time, %%capture): the same cell, one line down."""
        count = self.execution_count
        filename = "<Cell In[%d]>" % count
        source = "\n" + _transform.transform_cell(code)
        tree = ast.parse(source, filename=filename, mode="exec")
        show = display_last and bool(tree.body) and isinstance(tree.body[-1], ast.Expr) and not code.rstrip().endswith(";")
        value = self._run_tree(tree, filename, show or not display_last and bool(tree.body)
                               and isinstance(tree.body[-1], ast.Expr))
        if show and value is not None:
            self.displayhook(value, count)
            return None
        return value

    def _run_tree(self, tree, filename, last_is_value):
        body = list(tree.body)
        last = None
        if last_is_value and body and isinstance(body[-1], ast.Expr):
            last = body.pop()
        flags = getattr(ast, "PyCF_ALLOW_TOP_LEVEL_AWAIT", 0)
        if body:
            code = compile(ast.Module(body=body, type_ignores=[]), filename, "exec", flags=flags, dont_inherit=True)
            self._run_code(code)
        if last is not None:
            code = compile(ast.Expression(last.value), filename, "eval", flags=flags, dont_inherit=True)
            return self._run_code(code)
        return None

    def _run_code(self, code):
        value = eval(code, self.user_ns)
        if code.co_flags & inspect.CO_COROUTINE:
            import asyncio
            if self._async_loop is None or self._async_loop.is_closed():
                self._async_loop = asyncio.new_event_loop()
            value = self._async_loop.run_until_complete(value)
        return value

    def displayhook(self, value, count):
        if value is None:
            return
        ns = self.user_ns
        ns["___"], ns["__"], ns["_"] = ns.get("__", ""), ns.get("_", ""), value
        ns["Out"][count] = value
        ns["_%d" % count] = value
        data, md = _display.mimebundle(value)
        self.stdout.flush()
        self.stderr.flush()
        self.publish("execute_result", {"execution_count": count, "data": data, "metadata": md})

    def _flush_figures(self):
        if "matplotlib.pyplot" not in sys.modules:
            return
        try:
            import matplotlib
            if matplotlib.get_backend() == _magics.INLINE:
                from . import mpl_backend
                mpl_backend.flush_figures()
        except Exception:
            self.log(traceback.format_exc())

    # ---- Errors ---------------------------------------------------------------------

    def _error(self, exc, silent, syntax=False):
        content = {"ename": type(exc).__name__, "evalue": str(exc),
                   "traceback": self.format_traceback(exc, syntax=syntax)}
        if not silent:
            self.stdout.flush()
            self.stderr.flush()
            self.publish("error", content)
        return content

    def _usage_error(self, exc, silent):
        content = {"ename": "UsageError", "evalue": str(exc), "traceback": ["%sUsageError%s: %s" % (RED, RESET, exc)]}
        if not silent:
            self.publish("error", content)
        return content

    @staticmethod
    def _where(filename, lineno, name):
        m = _CELL_FILE.match(filename or "")
        place = "Cell In[%s], line %d" % (m.group(1), lineno) if m else "File %s:%d" % (filename, lineno)
        if name and name != "<module>":
            place += ", in %s" % name
        return place

    def format_traceback(self, exc, syntax=False):
        """IPython's layout, colored: the cell and line ("Cell In[3], line 5"), its context, the error."""
        if syntax and isinstance(exc, SyntaxError):
            lines = ["  %s%s%s" % (CYAN, self._where(exc.filename, exc.lineno or 1, None), RESET)]
            if exc.text:
                text = exc.text.rstrip("\n")
                stripped = text.lstrip()
                lines.append("    " + stripped)
                if exc.offset:
                    lines.append("    " + " " * max(0, exc.offset - 1 - (len(text) - len(stripped))) + "^")
            lines.append("%s%s%s: %s" % (RED, type(exc).__name__, RESET, exc.msg))
            return ["\n".join(lines)]
        chain = []
        seen = set()
        e = exc
        while e is not None and id(e) not in seen and len(chain) < 4:
            seen.add(id(e))
            chain.append(e)
            e = e.__cause__ or (None if e.__suppress_context__ else e.__context__)
        out = []
        for i, err in enumerate(reversed(chain)):
            if i:
                cause = chain[len(chain) - i].__cause__ is not None
                out.append("\n%s%s%s\n" % (DIM, "The above exception was the direct cause of the following exception:"
                                             if cause else "During handling of the above exception, another exception occurred:",
                                             RESET))
            out.extend(self._format_one(err))
        return out

    def _format_one(self, err):
        name = type(err).__name__
        blocks = ["%s%s%s" % (RED, "-" * 75, RESET),
                  "%s%-42s%sTraceback (most recent call last)" % (RED, name, RESET)]
        frames = [f for f in traceback.extract_tb(err.__traceback__)
                  if not (f.filename or "").startswith(_PACKAGE_DIR)]
        for f in frames:
            lines = ["%s%s%s" % (CYAN, self._where(f.filename, f.lineno or 0, f.name), RESET)]
            first = max(1, (f.lineno or 1) - 2)
            for n in range(first, (f.lineno or 1) + 1):
                text = linecache.getline(f.filename, n).rstrip("\n")
                if n == f.lineno:
                    lines.append("%s----> %d%s %s" % (GREEN, n, RESET, text))
                elif text.strip():
                    lines.append("%s      %d%s %s" % (GREEN, n, RESET, text))
            blocks.append("\n".join(lines))
        message = str(err)
        blocks.append("%s%s%s: %s" % (BOLD_RED, name, RESET, message) if message else "%s%s%s" % (BOLD_RED, name, RESET))
        return blocks

    # ---- stdin ---------------------------------------------------------------------

    def _on_stdin(self, frames):
        try:
            idents, msg = self.session.deserialize(frames)
        except Exception:
            return
        if msg["msg_type"] == "input_reply":
            self.input_replies.put(msg)

    def _input(self, prompt="", password=False):
        if not self.allow_stdin:
            raise StdinNotImplementedError("input() was called, but this frontend does not support input requests.")
        self.stdout.flush()
        self.stderr.flush()
        while True:
            try:
                self.input_replies.get_nowait()
            except queue.Empty:
                break
        identity = bytes(self.exec_idents[0]) if self.exec_idents else b""
        frames = self.session.serialize("input_request", {"prompt": str(prompt), "password": bool(password)},
                                        self.exec_parent, idents=[identity])
        if identity and self.stdin_socket.has(identity):
            self.stdin_socket.send(frames)
        else:
            self.stdin_socket.send_latest(frames[1:])
        while True:
            try:
                reply = self.input_replies.get(timeout=0.1)
                break
            except queue.Empty:
                if self.shutting_down:
                    raise EOFError()
        return reply["content"].get("value", "")

    # ---- Completion, documentation, history ------------------------------------------------

    def _on_complete_request(self, idents, msg):
        c = msg["content"]
        code = c.get("code", "")
        cursor = c.get("cursor_pos", len(code))
        if cursor is None:
            cursor = len(code)
        try:
            matches, start, end, kinds = _completion.complete(
                code, cursor, self.user_ns, list(_magics.LINE) + list(self.custom_line))
        except Exception:
            matches, start, end, kinds = [], cursor, cursor, []
        typed = [{"start": start, "end": end, "text": m, "type": k} for m, k in zip(matches, kinds)]
        self.reply(self.shell_socket, idents, msg, "complete_reply",
                   {"status": "ok", "matches": matches, "cursor_start": start, "cursor_end": end,
                    "metadata": {"_jupyter_types_experimental": typed}})

    def _on_inspect_request(self, idents, msg):
        c = msg["content"]
        code = c.get("code", "")
        cursor = c.get("cursor_pos", len(code))
        text = None
        try:
            expr = _completion.name_at(code, cursor)
            if expr:
                text = _completion.documentation(expr, self.user_ns, int(c.get("detail_level", 0) or 0))
        except Exception:
            text = None
        self.reply(self.shell_socket, idents, msg, "inspect_reply",
                   {"status": "ok", "found": text is not None, "data": {"text/plain": text} if text else {},
                    "metadata": {}})

    def _on_is_complete_request(self, idents, msg):
        status, indent = _transform.is_complete(msg["content"].get("code", ""))
        content = {"status": status}
        if status == "incomplete":
            content["indent"] = indent
        self.reply(self.shell_socket, idents, msg, "is_complete_reply", content)

    def _on_history_request(self, idents, msg):
        c = msg["content"]
        kind = c.get("hist_access_type", "tail")
        entries = list(self.history)
        if kind == "tail":
            entries = entries[-int(c.get("n", 10) or 10):]
        elif kind == "range":
            start, stop = int(c.get("start", 0) or 0), c.get("stop")
            entries = [e for e in entries if e[1] >= start and (stop is None or e[1] < int(stop))]
        elif kind == "search":
            import fnmatch
            pattern = c.get("pattern", "*")
            entries = [e for e in entries if fnmatch.fnmatch(e[2], pattern)]
            if c.get("unique"):
                seen = set()
                entries = [e for e in entries if not (e[2] in seen or seen.add(e[2]))]
            if c.get("n"):
                entries = entries[-int(c["n"]):]
        if c.get("output"):
            entries = [(s, n, (code, None)) for s, n, code in entries]
        self.reply(self.shell_socket, idents, msg, "history_reply", {"status": "ok", "history": entries})

    def _on_comm_info_request(self, idents, msg):
        self.reply(self.shell_socket, idents, msg, "comm_info_reply", {"status": "ok", "comms": {}})

    def _on_comm_open(self, idents, msg):
        # no widget support: the comm is closed at once, as for an unknown target
        self.publish("comm_close", {"comm_id": msg["content"].get("comm_id"), "data": {}}, parent=msg["header"])

    def _on_comm_msg(self, idents, msg):
        pass

    def _on_comm_close(self, idents, msg):
        pass

    def _on_shutdown_request(self, idents, msg):
        self._shutdown(idents, msg, self.shell_socket)

    # ---- The control channel -----------------------------------------------------------

    def _control_thread(self):
        while True:
            frames = self.control_queue.get()
            try:
                idents, msg = self.session.deserialize(frames)
            except Exception:
                continue
            t = msg["msg_type"]
            self.publish("status", {"execution_state": "busy"}, parent=msg["header"])
            try:
                if t == "interrupt_request":
                    self.reply(self.control_socket, idents, msg, "interrupt_reply", {"status": "ok"})
                    self.interrupt()
                elif t == "shutdown_request":
                    self._shutdown(idents, msg, self.control_socket)
                elif t == "kernel_info_request":
                    self.reply(self.control_socket, idents, msg, "kernel_info_reply", self.kernel_info())
                elif t == "usage_request":
                    self.reply(self.control_socket, idents, msg, "usage_reply", _usage())
                elif t == "debug_request":
                    c = msg["content"]
                    self.reply(self.control_socket, idents, msg, "debug_reply",
                               {"type": "response", "request_seq": c.get("seq", 0), "success": False,
                                "command": c.get("command", ""), "message": "Sphere's kernel has no debugger."})
            except Exception:
                self.log(traceback.format_exc())
            finally:
                self.publish("status", {"execution_state": "idle"}, parent=msg["header"])

    def interrupt(self):
        """Stops the running cell: a real SIGINT, which also wakes a sleeping call."""
        if not self.executing:
            return
        try:
            if os.name == "nt":
                signal.raise_signal(signal.SIGINT)
            else:
                signal.pthread_kill(self._main_ident, signal.SIGINT)
        except Exception:
            import _thread
            _thread.interrupt_main()

    def _shutdown(self, idents, msg, sock):
        restart = bool(msg["content"].get("restart", False))
        content = {"status": "ok", "restart": restart}
        self.reply(sock, idents, msg, "shutdown_reply", content)
        self.publish("shutdown_reply", content, parent=msg["header"])
        self.shutting_down = True

        def leave():
            try:
                self.stdout.flush()
                self.stderr.flush()
                self.loop.drain(1.0)
            finally:
                os._exit(0)

        threading.Timer(0.3, leave).start()


def _big_pipe():
    """A pipe with a large buffer: C code writing a burst must not wait on the reader."""
    if os.name == "nt":
        import _winapi
        import msvcrt
        rh, wh = _winapi.CreatePipe(None, 1 << 20)
        return msvcrt.open_osfhandle(rh, os.O_RDONLY), msvcrt.open_osfhandle(wh, 0)
    r, w = os.pipe()
    try:
        import fcntl
        fcntl.fcntl(w, 1031, 1 << 20)  # F_SETPIPE_SZ, Linux only
    except Exception:
        pass
    return r, w


_last_cpu = [time.process_time(), time.monotonic()]


def _usage():
    """What jupyter-resource-usage asks a kernel: its CPU and memory, the host's memory."""
    cpu, wall = time.process_time(), time.monotonic()
    percent = 100.0 * (cpu - _last_cpu[0]) / max(1e-6, wall - _last_cpu[1])
    _last_cpu[0], _last_cpu[1] = cpu, wall
    rss, total, available = _memory()
    out = {"hostname": socket.gethostname(), "pid": os.getpid(), "kernel_cpu": round(percent, 1),
           "kernel_memory": rss, "cpu_count": os.cpu_count()}
    if total:
        out["host_virtual_memory"] = {"total": total, "available": available, "used": total - available,
                                      "percent": round(100.0 * (total - available) / total, 1)}
    return out


def _memory():
    rss = total = available = 0
    try:
        if os.name == "nt":
            import ctypes
            from ctypes import wintypes

            class PMC(ctypes.Structure):
                _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD),
                            ("PeakWorkingSetSize", ctypes.c_size_t), ("WorkingSetSize", ctypes.c_size_t),
                            ("QuotaPeakPagedPoolUsage", ctypes.c_size_t), ("QuotaPagedPoolUsage", ctypes.c_size_t),
                            ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t), ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                            ("PagefileUsage", ctypes.c_size_t), ("PeakPagefileUsage", ctypes.c_size_t)]

            class MSX(ctypes.Structure):
                _fields_ = [("dwLength", wintypes.DWORD), ("dwMemoryLoad", wintypes.DWORD),
                            ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                            ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
                            ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
                            ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]

            pmc = PMC()
            pmc.cb = ctypes.sizeof(PMC)
            k32 = ctypes.WinDLL("kernel32")
            # The pseudo-handle of the process is -1 as a 64-bit HANDLE: undeclared, ctypes would pass a 32-bit int.
            k32.GetCurrentProcess.restype = wintypes.HANDLE
            get_info = getattr(k32, "K32GetProcessMemoryInfo", None) or ctypes.WinDLL("psapi").GetProcessMemoryInfo
            get_info.argtypes = [wintypes.HANDLE, ctypes.POINTER(PMC), wintypes.DWORD]
            get_info.restype = wintypes.BOOL
            if get_info(k32.GetCurrentProcess(), ctypes.byref(pmc), pmc.cb):
                rss = int(pmc.WorkingSetSize)
            msx = MSX()
            msx.dwLength = ctypes.sizeof(MSX)
            if k32.GlobalMemoryStatusEx(ctypes.byref(msx)):
                total, available = int(msx.ullTotalPhys), int(msx.ullAvailPhys)
        else:
            with open("/proc/self/statm") as f:
                rss = int(f.read().split()[1]) * os.sysconf("SC_PAGE_SIZE")
            with open("/proc/meminfo") as f:
                info = dict(line.split(":", 1) for line in f)
            total = int(info["MemTotal"].split()[0]) * 1024
            available = int(info.get("MemAvailable", info["MemFree"]).split()[0]) * 1024
    except Exception:
        pass
    return rss, total, available
