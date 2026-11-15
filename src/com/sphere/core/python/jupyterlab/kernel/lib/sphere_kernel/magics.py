"""The magics of Sphere's kernel.

IPython's everyday magics, written again on the standard library (%time,
%timeit, %prun, %who, %whos, %reset, %cd, %env, %pip, %run, %load, %matplotlib,
%%writefile, %%bash, %%html, %%capture...), and a few for physics notebooks:

    %%cpp       compiles the cell as C++ (g++, clang++, or SPHERE_CXX) and runs it;
                a cell without main() becomes main's body, with the usual headers
    %deps       where each variable comes from: the cell that made it, what from
    %stale      the variables computed from a value that has changed since
    %kernel     what this kernel is and what it found installed

!command runs a shell command; x = !command keeps its output lines; {expr}
and $name in a magic or shell line are replaced by their values.
"""

import ast
import builtins
import io
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import threading
import time

LINE = {}
CELL = {}


class UsageError(Exception):
    """A magic used wrongly: shown as one line, without a traceback."""


def line_magic(*names):
    def register(fn):
        for name in names:
            LINE[name] = fn
        return fn
    return register


def cell_magic(*names):
    def register(fn):
        for name in names:
            CELL[name] = fn
        return fn
    return register


def names():
    return sorted(["%" + n for n in LINE] + ["%%" + n for n in CELL])


def expand(kernel, text):
    """{expr} and $name replaced by their values, as IPython does in magic and shell lines."""
    ns = kernel.user_ns

    def brace(m):
        try:
            return str(eval(m.group(1), ns))
        except Exception:
            return m.group(0)

    text = re.sub(r"(?<!\{)\{([^{}\n]+)\}(?!\})", brace, text)
    return re.sub(r"\$(\$|[A-Za-z_]\w*)",
                  lambda m: "$" if m.group(1) == "$" else str(ns[m.group(1)]) if m.group(1) in ns else m.group(0), text)


def run_line(kernel, name, line):
    fn = LINE.get(name) or kernel.custom_line.get(name)
    if fn is None:
        if name in CELL:
            raise UsageError("%%%s is a cell magic: put it on the cell's first line as %%%%%s." % (name, name))
        raise UsageError("Line magic function `%%%s` not found." % name)
    if fn in kernel.custom_line.values():
        return fn(expand(kernel, line))
    return fn(kernel, expand(kernel, line))


def run_cell(kernel, name, line, cell):
    fn = CELL.get(name) or kernel.custom_cell.get(name)
    if fn is None:
        raise UsageError("Cell magic `%%%%%s` not found." % name)
    if fn in kernel.custom_cell.values():
        return fn(expand(kernel, line), cell)
    return fn(kernel, expand(kernel, line), cell)


# ---- Shell ------------------------------------------------------------------------


class SList(list):
    """The lines of a command's output, with .n (joined by newlines), .s (by spaces) and .l."""

    @property
    def n(self):
        return "\n".join(self)

    @property
    def s(self):
        return " ".join(self)

    @property
    def l(self):  # noqa: E743
        return list(self)

    def grep(self, pattern):
        return SList(line for line in self if re.search(pattern, line))


def _decode(data):
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        import locale
        return data.decode(locale.getpreferredencoding(False) or "latin-1", "replace")


def _pump(stream, write):
    for raw in iter(lambda: stream.readline(), b""):
        write(_decode(raw))
    stream.close()


def stream_process(argv, shell=False, stdin_text=None, cwd=None, env=None):
    """Runs a process, its output streamed to the cell as it comes; returns its exit code."""
    proc = subprocess.Popen(argv, shell=shell, cwd=cwd, env=env,
                            stdin=subprocess.PIPE if stdin_text is not None else subprocess.DEVNULL,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    threads = [threading.Thread(target=_pump, args=(proc.stdout, sys.stdout.write), daemon=True),
               threading.Thread(target=_pump, args=(proc.stderr, sys.stderr.write), daemon=True)]
    for t in threads:
        t.start()
    if stdin_text is not None:
        try:
            proc.stdin.write(stdin_text.encode("utf-8"))
            proc.stdin.close()
        except OSError:
            pass
    try:
        while proc.poll() is None:
            time.sleep(0.02)
    except KeyboardInterrupt:
        proc.kill()
        raise
    finally:
        for t in threads:
            t.join(timeout=2)
        sys.stdout.flush()
        sys.stderr.flush()
    return proc.returncode


def system(kernel, cmd):
    code = stream_process(expand(kernel, cmd), shell=True)
    kernel.user_ns["_exit_code"] = code


def getoutput(kernel, cmd):
    proc = subprocess.run(expand(kernel, cmd), shell=True, stdin=subprocess.DEVNULL,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    return SList(_decode(proc.stdout).splitlines())


# ---- Timing -------------------------------------------------------------------------


def format_time(seconds, precision=3):
    if seconds < 0:
        seconds = 0.0
    for unit, scale in (("s", 1.0), ("ms", 1e3), ("µs", 1e6), ("ns", 1e9)):
        if seconds >= 1.0 / scale or unit == "ns":
            value = seconds * scale
            return ("%." + str(precision) + "g %s") % (value, unit)
    return "%g s" % seconds


def _times():
    t = os.times()
    return time.perf_counter(), t.user, t.system


def _report_times(start, end):
    wall = end[0] - start[0]
    user = end[1] - start[1]
    sys_ = end[2] - start[2]
    print("CPU times: user %s, sys: %s, total: %s" % (format_time(user), format_time(sys_), format_time(user + sys_)))
    print("Wall time: %s" % format_time(wall))


@line_magic("time")
def time_line(kernel, line):
    if not line.strip():
        return None
    start = _times()
    try:
        value = kernel.run_body(line)
    finally:
        _report_times(start, _times())
    return value


@cell_magic("time")
def time_cell(kernel, line, cell):
    start = _times()
    try:
        value = kernel.run_body(cell, display_last=True)
    finally:
        _report_times(start, _times())
    return value


class TimeitResult:
    def __init__(self, loops, repeat, best, worst, all_runs, precision=3):
        self.loops = loops
        self.repeat = repeat
        self.best = best
        self.worst = worst
        self.all_runs = all_runs
        self.timings = [t / loops for t in all_runs]
        n = len(self.timings)
        self.average = sum(self.timings) / n
        self.stdev = (sum((t - self.average) ** 2 for t in self.timings) / n) ** 0.5
        self._precision = precision

    def __repr__(self):
        p = self._precision
        return "%s ± %s per loop (mean ± std. dev. of %d run%s, %s loop%s each)" % (
            format_time(self.average, p), format_time(self.stdev, p), self.repeat, "s" if self.repeat != 1 else "",
            "{:,}".format(self.loops), "s" if self.loops != 1 else "")


def _timeit(kernel, args, stmt, setup):
    import timeit
    opts = {"n": None, "r": 7, "p": 3}
    quiet = keep = False
    tokens = shlex.split(args, posix=os.name != "nt") if args else []
    rest = []
    i = 0
    while i < len(tokens):
        t = tokens[i]
        if t in ("-n", "-r", "-p") and i + 1 < len(tokens):
            opts[t[1]] = int(tokens[i + 1])
            i += 2
            continue
        if t == "-q":
            quiet = True
        elif t == "-o":
            keep = True
        else:
            rest.append(t)
        i += 1
    if stmt is None:
        stmt = " ".join(rest) if rest else ""
        stmt = args[args.find(stmt):] if stmt and stmt in args else stmt
    if not stmt.strip():
        raise UsageError("%timeit needs a statement to time.")
    from .transform import transform_cell
    timer = timeit.Timer(stmt=transform_cell(stmt), setup=transform_cell(setup or "pass"), globals=kernel.user_ns)
    number = opts["n"]
    if number is None:
        number = 1
        for _ in range(10):
            if timer.timeit(number) >= 0.2:
                break
            number *= 10
    runs = timer.repeat(repeat=opts["r"], number=number)
    result = TimeitResult(number, opts["r"], min(runs), max(runs), runs, opts["p"])
    if not quiet:
        print(result)
    return result if keep else None


@line_magic("timeit")
def timeit_line(kernel, line):
    m = re.match(r"^((?:\s*-[nrp]\s+\d+|\s*-[qo])*)\s*(.*)$", line, re.S)
    return _timeit(kernel, m.group(1), m.group(2), None)


@cell_magic("timeit")
def timeit_cell(kernel, line, cell):
    m = re.match(r"^((?:\s*-[nrp]\s+\d+|\s*-[qo])*)\s*(.*)$", line, re.S)
    return _timeit(kernel, m.group(1), cell, m.group(2))


def _profile(kernel, code):
    import cProfile
    import pstats
    from .transform import transform_cell
    profiler = cProfile.Profile()
    profiler.runctx(compile(transform_cell(code), "<profiled>", "exec"), kernel.user_ns, kernel.user_ns)
    out = io.StringIO()
    stats = pstats.Stats(profiler, stream=out).strip_dirs().sort_stats("cumulative")
    stats.print_stats(25)
    print(out.getvalue())


@line_magic("prun")
def prun_line(kernel, line):
    _profile(kernel, line)


@cell_magic("prun")
def prun_cell(kernel, line, cell):
    _profile(kernel, cell)


# ---- Namespace ---------------------------------------------------------------------


def _user_items(kernel):
    for name, value in sorted(kernel.user_ns.items()):
        if name.startswith("_") or name in kernel.hidden:
            continue
        yield name, value


@line_magic("who")
def who(kernel, line):
    wanted = set(line.split())
    found = [n for n, v in _user_items(kernel)
             if not wanted or type(v).__name__ in wanted]
    print("\t".join(found) if found else "Interactive namespace is empty.")


@line_magic("whos")
def whos(kernel, line):
    from .completion import describe_data
    rows = []
    for name, value in _user_items(kernel):
        info = describe_data(value)
        if not info:
            text = repr(value) if not callable(value) or isinstance(value, (list, dict)) else ""
            try:
                info = text if len(text) <= 60 else text[:57] + "..."
            except Exception:
                info = ""
        rows.append((name, type(value).__name__, info.replace("\n", " ")))
    if not rows:
        print("Interactive namespace is empty.")
        return
    w1 = max(8, max(len(r[0]) for r in rows))
    w2 = max(4, max(len(r[1]) for r in rows))
    print("%-*s   %-*s   %s" % (w1, "Variable", w2, "Type", "Data/Info"))
    print("-" * (w1 + w2 + 20))
    for r in rows:
        print("%-*s   %-*s   %s" % (w1, r[0], w2, r[1], r[2]))


@line_magic("reset")
def reset(kernel, line):
    kernel.reset_namespace()
    print("Namespace cleared.")


@line_magic("xdel")
def xdel(kernel, line):
    for name in line.split():
        kernel.user_ns.pop(name, None)
        kernel.provenance.forget(name)


# ---- Files, folders, environment ---------------------------------------------------


@line_magic("pwd")
def pwd(kernel, line):
    return os.getcwd()


_previous = []


@line_magic("cd")
def cd(kernel, line):
    target = line.strip().strip("\"'")
    if not target:
        target = os.path.expanduser("~")
    elif target == "-":
        if not _previous:
            raise UsageError("cd -: no previous folder.")
        target = _previous[-1]
    target = os.path.expanduser(target)
    old = os.getcwd()
    os.chdir(target)
    _previous.append(old)
    print(os.getcwd())


@line_magic("ls", "dir")
def ls(kernel, line):
    folder = line.strip().strip("\"'") or "."
    try:
        entries = sorted(os.scandir(folder), key=lambda e: (not e.is_dir(), e.name.lower()))
    except OSError as e:
        raise UsageError(str(e))
    for e in entries:
        print(e.name + ("/" if e.is_dir() else ""))


@line_magic("env")
def env(kernel, line):
    line = line.strip()
    if not line:
        return dict(os.environ)
    m = re.match(r"^(\w+)\s*[= ]\s*(.*)$", line)
    if m:
        os.environ[m.group(1)] = m.group(2).strip("\"'")
        print("env: %s=%s" % (m.group(1), m.group(2)))
        return None
    return os.environ.get(line)


@line_magic("set_env")
def set_env(kernel, line):
    return env(kernel, line)


@line_magic("pip")
def pip(kernel, line):
    code = stream_process([sys.executable, "-m", "pip"] + shlex.split(line, posix=os.name != "nt"))
    print("Note: restart the kernel to use updated packages." if code == 0 else "pip exited with code %d." % code)


@line_magic("conda")
def conda(kernel, line):
    exe = os.environ.get("CONDA_EXE") or shutil.which("conda") or shutil.which("mamba")
    if not exe:
        raise UsageError("conda was not found on this machine.")
    stream_process([exe] + shlex.split(line, posix=os.name != "nt") + ["--prefix", sys.prefix, "-y"])


@line_magic("run")
def run(kernel, line):
    args = shlex.split(line, posix=os.name != "nt")
    interactive = "-i" in args
    args = [a for a in args if a not in ("-i", "-t")]
    if not args:
        raise UsageError("%run file.py [args]")
    path = os.path.expanduser(args[0])
    if path.endswith(".ipynb"):
        with open(path, "r", encoding="utf-8") as f:
            nb = json.load(f)
        from .transform import transform_cell
        source = "\n".join(transform_cell("".join(c.get("source", [])) if isinstance(c.get("source"), list)
                                          else c.get("source", ""))
                           for c in nb.get("cells", []) if c.get("cell_type") == "code")
    else:
        with open(path, "r", encoding="utf-8") as f:
            source = f.read()
    saved_argv = sys.argv
    sys.argv = [path] + args[1:]
    ns = kernel.user_ns if interactive else {"__name__": "__main__", "__file__": path, "__builtins__": builtins}
    try:
        exec(compile(source, path, "exec"), ns)
    finally:
        sys.argv = saved_argv
    if not interactive:
        kernel.user_ns.update({k: v for k, v in ns.items() if not k.startswith("__")})


@line_magic("load")
def load(kernel, line):
    path = os.path.expanduser(line.strip().strip("\"'"))
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()
    kernel.payloads.append({"source": "set_next_input", "text": "# %load " + line.strip() + "\n" + text,
                            "replace": True})


@cell_magic("writefile")
def writefile(kernel, line, cell):
    args = shlex.split(line, posix=os.name != "nt")
    append = "-a" in args or "--append" in args
    args = [a for a in args if a not in ("-a", "--append")]
    if not args:
        raise UsageError("%%writefile [-a] file")
    path = os.path.expanduser(args[0])
    exists = os.path.exists(path)
    with open(path, "a" if append else "w", encoding="utf-8") as f:
        f.write(cell if cell.endswith("\n") else cell + "\n")
    print("%s %s" % ("Appending to" if append else "Overwriting" if exists else "Writing", path))


def _script(kernel, line, cell, argv, suffix=None):
    exe = shutil.which(argv[0])
    if exe is None:
        raise UsageError("%s was not found on this machine." % argv[0])
    if suffix is None:
        return stream_process([exe] + argv[1:], stdin_text=cell)
    fd, path = tempfile.mkstemp(suffix=suffix)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(cell)
    try:
        return stream_process([exe] + argv[1:] + [path])
    finally:
        os.unlink(path)


@cell_magic("bash")
def bash(kernel, line, cell):
    _script(kernel, line, cell, ["bash"])


@cell_magic("sh")
def sh(kernel, line, cell):
    _script(kernel, line, cell, ["sh" if shutil.which("sh") else "bash"])


@cell_magic("cmd")
def cmd(kernel, line, cell):
    _script(kernel, line, cell, ["cmd", "/Q", "/C"], suffix=".bat")


@cell_magic("powershell", "pwsh")
def powershell(kernel, line, cell):
    exe = "pwsh" if shutil.which("pwsh") else "powershell"
    _script(kernel, line, cell, [exe, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File"], suffix=".ps1")


@cell_magic("script")
def script(kernel, line, cell):
    argv = shlex.split(line, posix=os.name != "nt")
    if not argv:
        raise UsageError("%%script program [args]")
    _script(kernel, line, cell, argv)


def _raw(kernel, mime, cell):
    kernel.publish_display({mime: cell, "text/plain": cell}, {}, {}, False)


@cell_magic("html")
def html(kernel, line, cell):
    _raw(kernel, "text/html", cell)


@cell_magic("markdown", "md")
def markdown(kernel, line, cell):
    _raw(kernel, "text/markdown", cell)


@cell_magic("latex")
def latex(kernel, line, cell):
    _raw(kernel, "text/latex", cell)


@cell_magic("svg")
def svg(kernel, line, cell):
    _raw(kernel, "image/svg+xml", cell)


@cell_magic("javascript", "js")
def javascript(kernel, line, cell):
    _raw(kernel, "application/javascript", cell)


class CapturedIO:
    """What %%capture kept: .stdout, .stderr, .outputs; .show() replays them."""

    def __init__(self, stdout, stderr, outputs, kernel):
        self.stdout = stdout
        self.stderr = stderr
        self.outputs = outputs
        self._kernel = kernel

    def show(self):
        if self.stdout:
            sys.stdout.write(self.stdout)
        if self.stderr:
            sys.stderr.write(self.stderr)
        for data, md in self.outputs:
            self._kernel.publish_display(data, md, {}, False)

    __call__ = show

    def __repr__(self):
        return "<CapturedIO: %d chars of stdout, %d of stderr, %d outputs>" % (
            len(self.stdout), len(self.stderr), len(self.outputs))


@cell_magic("capture")
def capture(kernel, line, cell):
    args = line.split()
    keep_out = "--no-stdout" not in args
    keep_err = "--no-stderr" not in args
    keep_display = "--no-display" not in args
    target = next((a for a in args if not a.startswith("-")), None)
    with kernel.capturing() as box:
        kernel.run_body(cell, display_last=True)
    io_ = CapturedIO("".join(box["stdout"]) if keep_out else "", "".join(box["stderr"]) if keep_err else "",
                     box["outputs"] if keep_display else [], kernel)
    if target:
        kernel.user_ns[target] = io_


# ---- Plots, configuration, extensions ------------------------------------------------

BACKENDS = {"qt": "QtAgg", "qt5": "Qt5Agg", "qt6": "QtAgg", "tk": "TkAgg", "gtk": "GTK3Agg", "wx": "WXAgg",
            "osx": "MacOSX", "macosx": "MacOSX", "agg": "Agg", "widget": "module://ipympl.backend_nbagg",
            "ipympl": "module://ipympl.backend_nbagg", "notebook": "nbAgg", "nbagg": "nbAgg"}
INLINE = "module://sphere_kernel.mpl_backend"


@line_magic("matplotlib")
def matplotlib_magic(kernel, line):
    import matplotlib
    from . import mpl_backend
    mode = line.strip().lower() or "inline"
    if mode in ("inline", "svg", "retina", "png", "sphere"):
        mpl_backend.FORMAT = "svg" if mode == "svg" else "retina" if mode == "retina" else "png"
        matplotlib.use(INLINE)
    elif mode in BACKENDS:
        matplotlib.use(BACKENDS[mode])
    elif mode == "--list":
        print("Available matplotlib backends: inline, svg, retina, " + ", ".join(sorted(BACKENDS)))
    else:
        matplotlib.use(line.strip())


@line_magic("config")
def config(kernel, line):
    m = re.match(r"^\s*InlineBackend\.figure_formats?\s*=\s*(.+)$", line)
    if m:
        from . import mpl_backend
        value = ast.literal_eval(m.group(1))
        value = value[0] if isinstance(value, (list, set, tuple)) and value else value
        mpl_backend.FORMAT = str(value)
        return None
    if not line.strip():
        print("Configurable: InlineBackend.figure_format = 'png' | 'svg' | 'retina'")
        return None
    raise UsageError("Sphere's kernel only configures InlineBackend.figure_format.")


def _extension(kernel, name, reload):
    import importlib
    module = importlib.import_module(name)
    if reload:
        module = importlib.reload(module)
    hook = getattr(module, "load_ipython_extension", None)
    if hook is None:
        raise UsageError("%s has no load_ipython_extension()." % name)
    hook(kernel.shell)


@line_magic("load_ext")
def load_ext(kernel, line):
    _extension(kernel, line.strip(), False)


@line_magic("reload_ext")
def reload_ext(kernel, line):
    _extension(kernel, line.strip(), True)


@line_magic("lsmagic", "magic")
def lsmagic(kernel, line):
    print("Line magics:\n  " + "  ".join("%" + n for n in sorted(LINE)))
    print("Cell magics:\n  " + "  ".join("%%" + n for n in sorted(CELL)))
    print("\n!command runs a shell command, x = !command keeps its lines; obj? and obj?? document an object.")


@line_magic("history", "hist")
def history(kernel, line):
    numbered = "-n" in line.split()
    for session, number, source in kernel.history:
        print(("%4d: " % number if numbered else "") + source)


# ---- Physics notebooks --------------------------------------------------------------


@line_magic("deps")
def deps(kernel, line):
    rows = kernel.provenance.table(kernel.user_ns)
    if not rows:
        print("No variable recorded yet: run some cells.")
        return
    md = ["| variable | made by | computed from | status |", "|---|---|---|---|"]
    text = []
    for name, cell, sources, stale in rows:
        status = "stale: " + stale if stale else "up to date"
        md.append("| `%s` | [%s] | %s | %s |" % (name, cell, ", ".join("`%s`" % s for s in sources) or "-",
                                                ("⚠ " + stale) if stale else "✓"))
        text.append("%-20s [%s]  <- %-30s %s" % (name, cell, ", ".join(sources) or "-", status))
    kernel.publish_display({"text/markdown": "\n".join(md), "text/plain": "\n".join(text)}, {}, {}, False)


@line_magic("stale")
def stale(kernel, line):
    found = kernel.provenance.stale(kernel.user_ns)
    if not found:
        print("No stale variable: every value matches what it was computed from.")
        return
    for name, why in sorted(found.items()):
        print("%s: %s" % (name, why))
    print("\nRe-run the cells that compute them (or Restart & Run All).")


_CPP_HEADERS = ["iostream", "iomanip", "vector", "array", "string", "cmath", "cstdint", "algorithm", "numeric",
                "map", "unordered_map", "set", "memory", "random", "chrono", "functional", "utility", "tuple",
                "sstream", "fstream", "limits", "complex", "numbers"]


def _compiler():
    for candidate in (os.environ.get("SPHERE_CXX"), os.environ.get("CXX"), "g++", "clang++", "c++"):
        if candidate:
            exe = candidate if os.path.isfile(candidate) else shutil.which(candidate)
            if exe:
                return exe
    return None


@cell_magic("cpp", "c++")
def cpp(kernel, line, cell):
    """Compiles the cell as C++ and runs it: %%cpp [-std=c++20] [-O2] [-Iinc] [-lfoo] [-- program args]."""
    compiler = _compiler()
    if compiler is None:
        raise UsageError("No C++ compiler found: install g++ or clang++, or set SPHERE_CXX.")
    tokens = shlex.split(line, posix=os.name != "nt")
    program_args = []
    if "--" in tokens:
        program_args = tokens[tokens.index("--") + 1:]
        tokens = tokens[:tokens.index("--")]
    flags = tokens or []
    if not any(f.startswith("-std=") for f in flags):
        flags = ["-std=c++20"] + flags
    if not any(f.startswith("-O") for f in flags):
        flags = ["-O2"] + flags
    if re.search(r"\bint\s+main\s*\(", cell):
        source = cell
    else:
        source = "".join("#include <%s>\n" % h for h in _CPP_HEADERS) + "using namespace std;\n\nint main() {\n" + cell + "\n    return 0;\n}\n"
    folder = tempfile.mkdtemp(prefix="sphere_cpp_")
    src = os.path.join(folder, "cell.cpp")
    exe = os.path.join(folder, "cell.exe" if os.name == "nt" else "cell")
    with open(src, "w", encoding="utf-8") as f:
        f.write(source)
    env = dict(os.environ)
    # MinGW's runtime libraries live beside its compiler: the program needs them on PATH.
    env["PATH"] = os.path.dirname(compiler) + os.pathsep + env.get("PATH", "")
    started = time.perf_counter()
    build = subprocess.run([compiler] + flags + [src, "-o", exe], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           stdin=subprocess.DEVNULL, env=env)
    compiled = time.perf_counter() - started
    if build.returncode != 0:
        sys.stderr.write(_decode(build.stdout).replace(src, "cell.cpp"))
        shutil.rmtree(folder, ignore_errors=True)
        raise UsageError("compilation failed (%s)" % os.path.basename(compiler))
    if build.stdout:
        sys.stderr.write(_decode(build.stdout).replace(src, "cell.cpp"))
    started = time.perf_counter()
    try:
        code = stream_process([exe] + program_args, env=env, cwd=os.getcwd())
    finally:
        shutil.rmtree(folder, ignore_errors=True)
    ran = time.perf_counter() - started
    sys.stdout.flush()
    sys.stderr.write("\x1b[2m[%s: compiled in %s, ran in %s%s]\x1b[0m\n" % (
        os.path.basename(compiler), format_time(compiled), format_time(ran),
        "" if code == 0 else ", exit code %d" % code))


@line_magic("kernel", "sphere")
def kernel_info(kernel, line):
    from . import __version__
    found = []
    for name in ("numpy", "scipy", "pandas", "matplotlib", "awkward", "uproot", "hist", "torch", "tensorflow", "jax",
                 "pyhf", "ROOT", "jedi", "IPython"):
        spec = None
        try:
            import importlib.util
            spec = importlib.util.find_spec(name)
        except (ImportError, ValueError):
            pass
        if spec is not None:
            found.append(name)
    print("Sphere HEP kernel %s (homemade Jupyter kernel, pure standard library)" % __version__)
    print("Python %s at %s" % (sys.version.split()[0], sys.executable))
    print("pid %d, protocol 5.4, interrupt by message%s" % (
        os.getpid(), ", C/C++ output captured" if kernel.capturing_fds else ""))
    print("C++ compiler for %%%%cpp: %s" % (_compiler() or "none found"))
    print("Installed: " + (", ".join(found) or "none of the usual libraries"))
