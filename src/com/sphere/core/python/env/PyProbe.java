package com.sphere.core.python.env;

import com.sphere.components.rootview.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Everything about an environment in one start of its interpreter.
 *
 * The previous manager started Python eight times to learn what kind of
 * environment it was, then pip four times (list, check, an upgrade of pip
 * itself, list --outdated): some twenty seconds. This script reads the
 * installed distributions through importlib.metadata, evaluates their
 * requirements with the packaging library pip carries, and answers in one
 * JSON line, in about a third of a second.
 */
public final class PyProbe {

    private PyProbe() {
    }

    /** The probe. No backslash anywhere: it lives in a Java text block. */
    static final String SCRIPT = """
        import sys, os, json, sysconfig, site, re, zlib

        PROBE = "3"
        NL = chr(10)
        CR = chr(13)

        def norm(n):
            return re.sub("[-_.]+", "-", n).lower()

        # The marker environment, built without platform.uname(): on Windows it asks WMI, a tenth of a second.
        def iv(info):
            v = "%d.%d.%d" % (info.major, info.minor, info.micro)
            if info.releaselevel != "final":
                v += info.releaselevel[0] + str(info.serial)
            return v

        if os.name == "nt":
            machine = os.environ.get("PROCESSOR_ARCHITEW6432") or os.environ.get("PROCESSOR_ARCHITECTURE", "")
            system = "Windows"
        else:
            u = os.uname()
            machine = u.machine
            system = u.sysname
        menv = {
            "implementation_name": sys.implementation.name,
            "implementation_version": iv(sys.implementation.version),
            "os_name": os.name, "platform_machine": machine, "platform_system": system,
            "platform_release": "", "platform_version": "",
            "platform_python_implementation": {"cpython": "CPython", "pypy": "PyPy"}.get(sys.implementation.name, sys.implementation.name),
            "python_version": "%d.%d" % sys.version_info[:2],
            "python_full_version": iv(sys.version_info),
            "sys_platform": sys.platform, "extra": "",
        }
        VERSION_VARS = ("python_version", "python_full_version", "implementation_version")

        def vt(s):
            out = []
            for part in s.split("."):
                num = ""
                for ch in part:
                    if ch.isdigit():
                        num += ch
                    else:
                        break
                out.append(int(num) if num else 0)
            return out

        def vcompare(a, op, b):
            x = vt(a)
            y = vt(b)
            n = max(len(x), len(y))
            x = tuple(x + [0] * (n - len(x)))
            y = tuple(y + [0] * (n - len(y)))
            if op == "<":
                return x < y
            if op == "<=":
                return x <= y
            if op == ">":
                return x > y
            if op == ">=":
                return x >= y
            if op == "==":
                return x == y
            if op == "!=":
                return x != y
            raise ValueError(op)

        def tokens(m):
            out = []
            i = 0
            n = len(m)
            while i < n:
                c = m[i]
                if c.isspace():
                    i += 1
                elif c in "()":
                    out.append(c)
                    i += 1
                elif c == "'" or c == '"':
                    j = m.index(c, i + 1)
                    out.append(("s", m[i + 1:j]))
                    i = j + 1
                elif c in "<>=!~":
                    j = i
                    while j < n and m[j] in "<>=!~":
                        j += 1
                    out.append(("op", m[i:j]))
                    i = j
                else:
                    j = i
                    while j < n and (m[j].isalnum() or m[j] in "_."):
                        j += 1
                    if j == i:
                        raise ValueError(m)
                    out.append(("w", m[i:j]))
                    i = j
            return out

        def evaluate(m):
            # and, or, parentheses, comparisons: what PEP 508 markers are made of.
            toks = tokens(m)
            pos = [0]

            def peek():
                return toks[pos[0]] if pos[0] < len(toks) else None

            def take():
                t = toks[pos[0]]
                pos[0] += 1
                return t

            def value(t):
                if t[0] == "s":
                    return t[1], None
                if t[0] == "w" and t[1] in menv:
                    return menv[t[1]], t[1]
                raise ValueError(m)

            def compare():
                if peek() == "(":
                    take()
                    r = disjunction()
                    if take() != ")":
                        raise ValueError(m)
                    return r
                left, lvar = value(take())
                t = take()
                if t == ("w", "not"):
                    if take() != ("w", "in"):
                        raise ValueError(m)
                    op = "not in"
                elif t == ("w", "in"):
                    op = "in"
                elif t[0] == "op":
                    op = t[1]
                else:
                    raise ValueError(m)
                right, rvar = value(take())
                if op == "in":
                    return left in right
                if op == "not in":
                    return left not in right
                if lvar in VERSION_VARS or rvar in VERSION_VARS:
                    return vcompare(left, op, right)
                if op in ("==", "==="):
                    return left == right
                if op == "!=":
                    return left != right
                raise ValueError(m)

            def conjunction():
                r = compare()
                while peek() == ("w", "and"):
                    take()
                    r = compare() and r
                return r

            def disjunction():
                r = conjunction()
                while peek() == ("w", "or"):
                    take()
                    r = conjunction() or r
                return r

            r = disjunction()
            if pos[0] != len(toks):
                raise ValueError(m)
            return r

        marker_cache = {}

        def applies(marker):
            if not marker:
                return True
            r = marker_cache.get(marker)
            if r is None:
                if ("extra ==" in marker or "extra==" in marker) and " or " not in marker:
                    # An and-chain naming an extra never holds when no extra is asked for.
                    r = False
                else:
                    try:
                        r = bool(evaluate(marker))
                    except Exception:
                        r = True
                        for base in ("pip._vendor.packaging", "packaging"):
                            try:
                                mk = __import__(base + ".markers", fromlist=["Marker"])
                                r = bool(mk.Marker(marker).evaluate(dict(menv)))
                                break
                            except Exception:
                                pass
                marker_cache[marker] = r
            return r

        def split_req(r):
            body, _, marker = r.partition(";")
            body = body.strip()
            i = 0
            while i < len(body) and (body[i].isalnum() or body[i] in "._-"):
                i += 1
            name = body[:i]
            rest = body[i:].strip()
            extras = []
            if rest.startswith("["):
                j = rest.find("]")
                extras = [e.strip() for e in rest[1:j].split(",") if e.strip()]
                rest = rest[j + 1:].strip()
            if rest.startswith("(") and rest.endswith(")"):
                rest = rest[1:-1].strip()
            spec = "" if rest.startswith("@") else rest.replace(" ", "")
            return name, extras, spec, marker.strip()

        class Headers:
            # The headers of METADATA only: the email parser reads the whole README after them.
            def __init__(self, text):
                self.h = {}
                last = None
                for line in text.splitlines():
                    if not line:
                        break
                    if line[0].isspace() and last is not None:
                        vals = self.h[last]
                        vals[-1] = vals[-1] + NL + line.strip()
                        continue
                    k, sep, v = line.partition(":")
                    if not sep:
                        continue
                    last = k.strip().lower()
                    self.h.setdefault(last, []).append(v.strip())

            def get(self, k, default=None):
                v = self.h.get(k.lower())
                return v[0] if v else default

            def get_all(self, k):
                return self.h.get(k.lower())

        def home_of(meta):
            h = meta.get("Home-page") or ""
            for u in meta.get_all("Project-URL") or []:
                if "," in u:
                    label, url = u.split(",", 1)
                    if not h or label.strip().lower() in ("homepage", "home", "source", "repository"):
                        h = url.strip()
                        if label.strip().lower() in ("homepage", "home"):
                            break
            return h

        SIZE = re.compile(",([0-9]+)" + CR + "?$", re.M)
        FIRST = re.compile("^([^,/]*)", re.M)

        def roots_of(firsts):
            roots = set()
            for first in firsts:
                first = first.strip('"')
                if (not first or first.startswith("..") or first == "__pycache__" or first.endswith(".dist-info")
                        or first.endswith(".data") or first.endswith(".egg-info")):
                    continue
                if first.endswith(".py"):
                    roots.add(first[:-3])
                elif first.endswith(".pyd") or first.endswith(".so"):
                    roots.add(first.split(".", 1)[0])
                elif first.isidentifier():
                    roots.add(first)
            return sorted(roots)

        def folders():
            # The metadata folders on sys.path, in its order, read directly: importlib.metadata
            # does the same after a tenth of a second of imports.
            here = os.path.dirname(os.path.abspath(__file__))
            for entry in sys.path:
                if not entry or not os.path.isdir(entry) or os.path.abspath(entry) == here:
                    continue
                try:
                    names = os.listdir(entry)
                except OSError:
                    continue
                for n in names:
                    if n.endswith(".dist-info") or n.endswith(".egg-info"):
                        full = os.path.join(entry, n)
                        if os.path.isdir(full):
                            yield full

        def describe(folder):
            names = set(os.listdir(folder))

            def read(n):
                if n not in names:
                    return None
                with open(os.path.join(folder, n), encoding="utf-8", errors="replace") as f:
                    return f.read()

            meta = Headers(read("METADATA") or read("PKG-INFO") or "")
            name = meta.get("Name")
            if not name:
                return None
            text = read("RECORD") or ""
            tl = read("top_level.txt")
            top = [t.strip() for t in tl.splitlines() if t.strip()] if tl else []
            roots = roots_of(set(FIRST.findall(text)))
            raw = meta.get_all("Requires-Dist")
            if raw is None:
                # requires.txt of an egg-info: the lines before its first [extra] section.
                raw = []
                for line in (read("requires.txt") or "").splitlines():
                    line = line.strip()
                    if line.startswith("["):
                        break
                    if line and not line.startswith("#"):
                        raw.append(line)
            reqs = []
            for r in raw:
                rname, extras, spec, marker = split_req(r)
                if rname:
                    reqs.append({"raw": r, "name": norm(rname), "spec": spec, "extras": extras, "applies": applies(marker)})
            editable = False
            du = read("direct_url.json")
            if du:
                try:
                    editable = bool(json.loads(du).get("dir_info", {}).get("editable", False))
                except Exception:
                    pass
            lic = meta.get("License-Expression") or meta.get("License") or ""
            return {
                "name": name, "key": norm(name), "version": meta.get("Version") or "",
                "summary": meta.get("Summary") or "", "path": folder,
                "installer": (read("INSTALLER") or "").strip(),
                "requested": "REQUESTED" in names, "editable": editable,
                "size": sum(map(int, SIZE.findall(text))), "files": text.count(NL),
                "top": sorted(set(top) | set(roots)),
                "requires": reqs, "requires_python": meta.get("Requires-Python") or "",
                "license": lic.splitlines()[0][:80] if lic.strip() else "",
                "home": home_of(meta),
            }

        paths = sysconfig.get_paths()
        py = {
            "version": ".".join(str(x) for x in sys.version_info[:3]),
            "release": sys.version_info.releaselevel,
            "executable": sys.executable,
            "prefix": sys.prefix,
            "base_prefix": getattr(sys, "base_prefix", sys.prefix),
            "implementation": sys.implementation.name,
            "bits": 64 if sys.maxsize > 2 ** 32 else 32,
            "platform": sysconfig.get_platform(),
            "conda": os.path.isdir(os.path.join(sys.prefix, "conda-meta")),
            "purelib": paths.get("purelib") or "",
            "user_site": site.getusersitepackages() if hasattr(site, "getusersitepackages") else "",
            "user_site_enabled": bool(getattr(site, "ENABLE_USER_SITE", False)),
            "stdlib": sorted(getattr(sys, "stdlib_module_names", [])),
            "builtin": sorted(sys.builtin_module_names),
            "externally_managed": os.path.isfile(os.path.join(paths.get("stdlib") or "", "EXTERNALLY-MANAGED")),
            "pip": None,
        }
        pv = {}
        cfg = os.path.join(sys.prefix, "pyvenv.cfg")
        if os.path.isfile(cfg):
            with open(cfg, encoding="utf-8", errors="replace") as f:
                for line in f.read().splitlines():
                    if "=" in line:
                        k, v = line.split("=", 1)
                        pv[k.strip()] = v.strip()
        py["pyvenv"] = pv

        # What was read of each metadata folder, kept while the folder is unchanged.
        cache_file = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                  "env-%08x.json" % (zlib.crc32((sys.prefix + "|" + PROBE).encode("utf-8")) & 0xFFFFFFFF))
        try:
            with open(cache_file, encoding="utf-8") as f:
                cache = json.load(f)
        except Exception:
            cache = {}
        fresh = {}
        changed = False
        pkgs = {}
        dups = {}
        imports = {}
        for folder in folders():
            try:
                stamp = os.stat(folder).st_mtime_ns
                hit = cache.get(folder)
                if hit is not None and hit[0] == stamp:
                    p = hit[1]
                else:
                    p = describe(folder)
                    changed = True
                    if p is None:
                        continue
                fresh[folder] = [stamp, p]
                key = p["key"]
                if key in pkgs:
                    dups.setdefault(key, [pkgs[key]["path"]]).append(folder)
                    continue
                pkgs[key] = p
                for module in p["top"]:
                    imports.setdefault(module, []).append(key)
            except Exception:
                pass
        if changed or len(fresh) != len(cache):
            try:
                tmp = cache_file + ".part"
                with open(tmp, "w", encoding="utf-8") as f:
                    json.dump(fresh, f)
                os.replace(tmp, cache_file)
            except Exception:
                pass
        if "pip" in pkgs:
            py["pip"] = pkgs["pip"]["version"]

        leftovers = []
        dirs = set(x for x in [paths.get("purelib"), paths.get("platlib")] if x)
        try:
            dirs.update(site.getsitepackages())
        except Exception:
            pass
        for sd in dirs:
            if os.path.isdir(sd):
                for n in os.listdir(sd):
                    if n.startswith("~"):
                        leftovers.append(os.path.join(sd, n))

        sys.stdout.write(json.dumps({"python": py, "packages": list(pkgs.values()), "duplicates": dups,
                                     "leftovers": sorted(leftovers), "imports": imports}))
        """;

    /** Imports each module in turn, timing it; GUI toolkits kept off screen. No backslash: a text block. */
    static final String IMPORT_CHECK = """
        import sys, os, time, json, importlib
        os.environ.setdefault("MPLBACKEND", "Agg")
        os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
        with open(sys.argv[1], encoding="utf-8") as f:
            modules = json.load(f)
        SKIP = {"antigravity", "this", "turtle", "tkinter", "idlelib", "pygame", "pip", "setuptools", "pkg_resources",
                "_distutils_hack", "pythonwin", "win32", "pywin32_system32", "sitecustomize", "usercustomize", "test", "tests"}
        out = []
        for m in modules:
            if m in SKIP or m.startswith("_"):
                continue
            t = time.perf_counter()
            try:
                importlib.import_module(m)
                out.append([m, True, time.perf_counter() - t, ""])
            except BaseException as e:
                out.append([m, False, time.perf_counter() - t, type(e).__name__ + ": " + str(e)[:240]])
        sys.stdout.write("@@SPHERE@@" + json.dumps(out))
        """;

    /** One module imported: whether it worked, how long it took, what went wrong. */
    public record ImportResult(String module, boolean ok, double seconds, String error) {
    }

    /** Imports every module in one interpreter, as a user's script would; minutes at most. */
    public static List<ImportResult> importCheck(String executable, List<String> modules) throws IOException, InterruptedException {
        final Path dir = Path.of(System.getProperty("java.io.tmpdir"), "sphere-python");
        Files.createDirectories(dir);
        final Path script = dir.resolve("sphere_import_check_" + Integer.toHexString(IMPORT_CHECK.hashCode()) + ".py");
        if (!Files.isRegularFile(script)) Files.writeString(script, IMPORT_CHECK, StandardCharsets.UTF_8);
        final StringBuilder list = new StringBuilder("[");
        for (int i = 0; i < modules.size(); i++) list.append(i == 0 ? "" : ",").append('"').append(modules.get(i).replace("\"", "")).append('"');
        final Path file = Files.createTempFile(dir, "modules", ".json");
        Files.writeString(file, list.append(']').toString(), StandardCharsets.UTF_8);
        try {
            final ProcessBuilder pb = new ProcessBuilder(executable, "-X", "utf8", script.toString(), file.toString());
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.redirectErrorStream(true);
            pb.redirectInput(ProcessBuilder.Redirect.from(nullFile()));
            final Process p = pb.start();
            final byte[] bytes = p.getInputStream().readAllBytes();
            if (!p.waitFor(240, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("the imports took more than four minutes");
            }
            final String text = new String(bytes, StandardCharsets.UTF_8);
            final int at = text.lastIndexOf("@@SPHERE@@");
            if (at < 0) throw new IOException("the interpreter stopped while importing: " + lastLines(text, 4));
            final List<ImportResult> out = new ArrayList<>();
            for (Object o : Json.parse(text.substring(at + 10)) instanceof List<?> l ? l : List.of()) {
                if (o instanceof List<?> r && r.size() >= 4) {
                    out.add(new ImportResult(String.valueOf(r.get(0)), Boolean.TRUE.equals(r.get(1)),
                        r.get(2) instanceof Number n ? n.doubleValue() : 0, String.valueOf(r.get(3))));
                }
            }
            return out;
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static Path scriptFile;

    /** The probe written once to a file: a script passed with -c is mangled by Windows' quoting. */
    static synchronized Path script() throws IOException {
        if (scriptFile != null && Files.isRegularFile(scriptFile)) return scriptFile;
        final Path dir = Path.of(System.getProperty("java.io.tmpdir"), "sphere-python");
        Files.createDirectories(dir);
        scriptFile = dir.resolve("sphere_env_probe_" + Integer.toHexString(SCRIPT.hashCode()) + ".py");
        if (!Files.isRegularFile(scriptFile)) Files.writeString(scriptFile, SCRIPT, StandardCharsets.UTF_8);
        return scriptFile;
    }

    /** Probes an interpreter. */
    public static PyEnv run(String executable) throws IOException, InterruptedException {
        final long t0 = System.nanoTime();
        final String out = runScript(executable, script(), 60);
        final PyEnv env = parse(out);
        env.probeMillis = (System.nanoTime() - t0) / 1_000_000;
        return env;
    }

    /** Runs a Python file with UTF-8 output and answers its standard output. */
    static String runScript(String executable, Path file, int timeoutSeconds) throws IOException, InterruptedException {
        final ProcessBuilder pb = new ProcessBuilder(executable, "-X", "utf8", file.toString());
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        pb.redirectInput(ProcessBuilder.Redirect.from(nullFile()));
        final Process p = pb.start();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final Thread drain = Thread.ofVirtual().start(() -> copy(p.getErrorStream(), err));
        final byte[] bytes = p.getInputStream().readAllBytes();
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("the interpreter did not answer in " + timeoutSeconds + " s");
        }
        drain.join(2000);
        final String text = new String(bytes, StandardCharsets.UTF_8).strip();
        if (p.exitValue() != 0 || text.isEmpty()) {
            final String e = err.toString(StandardCharsets.UTF_8).strip();
            throw new IOException(e.isEmpty() ? "the interpreter exited with code " + p.exitValue() : lastLines(e, 6));
        }
        return text;
    }

    static java.io.File nullFile() {
        return new java.io.File(System.getProperty("os.name").toLowerCase().contains("win") ? "NUL" : "/dev/null");
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        try {
            in.transferTo(out);
        } catch (IOException ignored) {
            // the process went away
        }
    }

    static String lastLines(String text, int n) {
        final String[] lines = text.split("\\R");
        final StringBuilder b = new StringBuilder();
        for (int i = Math.max(0, lines.length - n); i < lines.length; i++) b.append(lines[i]).append('\n');
        return b.toString().strip();
    }

    /** The probe's JSON as an environment. */
    static PyEnv parse(String json) {
        final int start = json.indexOf('{');
        final Object root = Json.parse(start > 0 ? json.substring(start) : json);
        if (!(root instanceof Map<?, ?>)) throw new IllegalStateException("the probe did not answer JSON");
        final PyEnv env = new PyEnv();
        final Object py = Json.get(root, "python");
        env.version = Json.text(py, "version", "");
        env.releaseLevel = Json.text(py, "release", "final");
        env.executable = Json.text(py, "executable", "");
        env.prefix = Json.text(py, "prefix", "");
        env.basePrefix = Json.text(py, "base_prefix", env.prefix);
        env.implementation = Json.text(py, "implementation", "cpython");
        env.bits = (int) Json.number(py, "bits", 64);
        env.platform = Json.text(py, "platform", "");
        env.conda = bool(Json.get(py, "conda"));
        env.purelib = Json.text(py, "purelib", "");
        env.userSite = Json.text(py, "user_site", "");
        env.userSiteEnabled = bool(Json.get(py, "user_site_enabled"));
        env.externallyManaged = bool(Json.get(py, "externally_managed"));
        final Object pip = Json.get(py, "pip");
        env.pip = pip == null ? null : String.valueOf(pip);
        for (Object s : Json.list(py, "stdlib")) env.stdlib.add(String.valueOf(s));
        for (Object s : Json.list(py, "builtin")) env.stdlib.add(String.valueOf(s));
        if (Json.get(py, "pyvenv") instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) env.pyvenv.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        }
        for (Object o : Json.list(root, "packages")) {
            final PyEnv.Pkg p = new PyEnv.Pkg();
            p.name = Json.text(o, "name", "");
            p.key = Json.text(o, "key", Pep440.normalize(p.name));
            p.version = Json.text(o, "version", "");
            p.summary = Json.text(o, "summary", "");
            p.path = Json.text(o, "path", "");
            p.installer = Json.text(o, "installer", "");
            p.requested = bool(Json.get(o, "requested"));
            p.editable = bool(Json.get(o, "editable"));
            p.size = Json.number(o, "size", 0);
            p.files = (int) Json.number(o, "files", 0);
            for (Object t : Json.list(o, "top")) p.top.add(String.valueOf(t));
            p.requiresPython = Json.text(o, "requires_python", "");
            p.license = Json.text(o, "license", "");
            p.home = Json.text(o, "home", "");
            for (Object r : Json.list(o, "requires")) {
                final PyEnv.Req q = new PyEnv.Req();
                q.raw = Json.text(r, "raw", "");
                q.name = Json.text(r, "name", "");
                if (q.name.isEmpty()) q.name = nameOf(q.raw);
                q.spec = Json.text(r, "spec", "");
                for (Object x : Json.list(r, "extras")) q.extras.add(String.valueOf(x));
                q.applies = Json.get(r, "applies") == null || bool(Json.get(r, "applies"));
                p.requires.add(q);
            }
            env.packages.put(p.key, p);
        }
        for (Object l : Json.list(root, "leftovers")) env.leftovers.add(String.valueOf(l));
        if (Json.get(root, "duplicates") instanceof Map<?, ?> d) {
            for (Map.Entry<?, ?> e : d.entrySet()) {
                final List<String> where = new ArrayList<>();
                if (e.getValue() instanceof List<?> l) for (Object x : l) where.add(String.valueOf(x));
                env.duplicates.put(String.valueOf(e.getKey()), where);
            }
        }
        if (Json.get(root, "imports") instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                final List<String> dists = new ArrayList<>();
                if (e.getValue() instanceof List<?> l) for (Object x : l) dists.add(String.valueOf(x));
                env.imports.put(String.valueOf(e.getKey()), dists);
            }
        }
        env.link();
        env.findConflicts();
        return env;
    }

    /** The name at the start of a requirement, when the interpreter had no packaging library to read it. */
    static String nameOf(String raw) {
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*([A-Za-z0-9][A-Za-z0-9._-]*)").matcher(raw);
        return m.find() ? Pep440.normalize(m.group(1)) : "";
    }

    private static boolean bool(Object o) {
        return o instanceof Boolean b ? b : o != null && "true".equalsIgnoreCase(String.valueOf(o));
    }
}
