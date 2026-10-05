package com.sphere.core.python.env;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * What the manager knows beyond pip: for each package the newest version
 * this environment can actually take (one that every package depending on
 * it accepts and that supports this Python), the vulnerabilities of the
 * installed version, whether it was yanked; for the environment a health
 * score and the findings behind it, each with the fix that answers it.
 */
public final class PyAdvisor {

    private PyAdvisor() {
    }

    /* ------------------------------------------------------------------ */
    /* What PyPI says, package by package                                  */
    /* ------------------------------------------------------------------ */

    /**
     * Asks PyPI about every package, sixteen at a time, and calls back as
     * each one is known; completes when all are.
     */
    public static CompletableFuture<Void> enrich(PyEnv env, PyPi pypi, boolean prereleases, Consumer<PyEnv.Pkg> known) {
        return CompletableFuture.runAsync(() -> enrichNow(env, pypi, prereleases, known), Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * In batches: first the feeds and the installed releases of every
     * package (what the table needs: latest, vulnerabilities), then the
     * releases of the candidates for a safe upgrade, two per package per round.
     */
    public static void enrichNow(PyEnv env, PyPi pypi, boolean prereleases, Consumer<PyEnv.Pkg> known) {
        pypi.setPython(env.executable);
        final List<PyPi.Job> jobs = new ArrayList<>();
        for (PyEnv.Pkg p : env.packages.values()) {
            jobs.add(PyPi.projectJob(p.key));
            jobs.add(PyPi.releaseJob(p.key, p.version));
        }
        pypi.prefetch(jobs);
        final Map<PyEnv.Pkg, Candidates> pending = new LinkedHashMap<>();
        for (PyEnv.Pkg p : env.packages.values()) {
            try {
                final Candidates c = first(env, p, pypi, prereleases);
                if (c != null) pending.put(p, c);
                else known.accept(p);
            } catch (RuntimeException e) {
                known.accept(p);
            }
        }
        for (int round = 0; round < 4 && !pending.isEmpty(); round++) {
            final List<PyPi.Job> next = new ArrayList<>();
            for (Map.Entry<PyEnv.Pkg, Candidates> e : pending.entrySet()) {
                final Candidates c = e.getValue();
                for (int k = c.at; k < Math.min(c.list.size(), c.at + 2); k++) {
                    next.add(PyPi.releaseJob(e.getKey().key, c.list.get(k).version()));
                }
            }
            pypi.prefetch(next);
            for (var it = pending.entrySet().iterator(); it.hasNext(); ) {
                final var e = it.next();
                if (resolve(env, e.getKey(), e.getValue(), pypi, 2)) {
                    it.remove();
                    known.accept(e.getKey());
                }
            }
        }
        for (PyEnv.Pkg p : pending.keySet()) {
            if (p.safeReason.isEmpty()) p.safeReason = "no allowed version found among the recent ones";
            known.accept(p);
        }
    }

    /** The versions to try for a package, newest first, and how far the trying went. */
    private static final class Candidates {
        final List<PyPi.Published> list = new ArrayList<>();
        int at;
        String blocked = "";
    }

    /** What the feed and the installed release say; the candidates still to check, or null when done. */
    private static Candidates first(PyEnv env, PyEnv.Pkg p, PyPi pypi, boolean prereleases) {
        final PyPi.Project project = pypi.project(p.key);
        p.project = project;
        if (!project.found) {
            p.notOnPyPi = !project.stale;
            return null;
        }
        p.installedRelease = pypi.release(p.key, p.version);
        final Pep440.Version installed = p.parsed();
        final boolean pre = prereleases || installed != null && installed.isPrerelease();
        final PyPi.Published newest = project.newest(pre);
        if (newest == null || installed == null) return null;
        p.latest = newest.version();
        p.jump = Pep440.jump(installed, newest.parsed());
        p.safeTarget = null;
        p.safeReason = "";
        if (p.jump == Pep440.Jump.NONE || p.jump == Pep440.Jump.DOWNGRADE) return null;
        // The newer versions every dependent accepts; this Python's support is checked on their releases.
        final Candidates c = new Candidates();
        final List<String[]> constraints = constraintsOn(env, p.key);
        for (PyPi.Published v : project.sorted(pre)) {
            if (v.parsed().compareTo(installed) <= 0) break;
            final String refused = refusedBy(constraints, v.parsed());
            if (refused != null) {
                if (c.blocked.isEmpty()) c.blocked = refused;
                continue;
            }
            c.list.add(v);
        }
        if (c.list.isEmpty()) {
            p.safeReason = c.blocked.isEmpty() ? "" : "held back: " + c.blocked;
            return null;
        }
        return c;
    }

    /** Checks up to n more candidates; true when the package is settled. */
    private static boolean resolve(PyEnv env, PyEnv.Pkg p, Candidates c, PyPi pypi, int n) {
        final Pep440.Version python = Pep440.parse(env.version);
        for (int k = 0; k < n && c.at < c.list.size(); k++, c.at++) {
            final PyPi.Published v = c.list.get(c.at);
            final PyPi.Release r = pypi.release(p.key, v.version());
            if (r.yanked) continue;
            if (!r.requiresPython.isEmpty() && python != null && !Pep440.specifier(r.requiresPython).contains(python, true)) {
                if (c.blocked.isEmpty()) c.blocked = v.version() + " needs Python " + r.requiresPython;
                continue;
            }
            p.safeTarget = v.version();
            p.safeReason = v.version().equals(p.latest) ? "" : "newest allowed: " + c.blocked;
            return true;
        }
        if (c.at >= c.list.size()) {
            p.safeReason = c.blocked.isEmpty() ? "" : "held back: " + c.blocked;
            return true;
        }
        return false;
    }

    /** What the packages depending on key ask of it: [dependent, specifier]. */
    public static List<String[]> constraintsOn(PyEnv env, String key) {
        final List<String[]> out = new ArrayList<>();
        for (PyEnv.Pkg d : env.packages.values()) {
            for (PyEnv.Req r : d.requires) {
                if (r.applies && r.name.equals(key) && !r.spec.isEmpty()) out.add(new String[]{d.name, r.spec});
            }
        }
        return out;
    }

    /** "astropy needs numpy<2" when a dependent refuses the version, else null. */
    static String refusedBy(List<String[]> constraints, Pep440.Version v) {
        for (String[] c : constraints) {
            if (!Pep440.specifier(c[1]).contains(v, true)) return c[0] + " needs " + c[1];
        }
        return null;
    }

    /** The newest known version of a package that every constraint accepts, or null. */
    public static String bestAllowed(PyEnv env, String key, PyPi.Project project, Collection<String> extraSpecs) {
        if (project == null) return null;
        final List<String[]> constraints = new ArrayList<>(constraintsOn(env, key));
        for (String s : extraSpecs) constraints.add(new String[]{"", s});
        for (PyPi.Published c : project.sorted(false)) {
            if (refusedBy(constraints, c.parsed()) == null) return c.version();
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* Health                                                              */
    /* ------------------------------------------------------------------ */

    public enum Severity { CRITICAL, HIGH, MEDIUM, LOW, INFO }

    /** What a fix does: pip install of specs, pip uninstall of names, removal of folders, a reinstall... */
    public enum FixKind { INSTALL, UNINSTALL, DELETE_FOLDERS, REINSTALL, NEW_VENV, NONE }

    public record Fix(FixKind kind, List<String> targets, String label) {
        public static Fix none() {
            return new Fix(FixKind.NONE, List.of(), "");
        }
    }

    public record Finding(Severity severity, String title, String detail, Fix fix) {
    }

    /** The end of life of each Python minor version, as python.org gives them. */
    static final Map<String, LocalDate> EOL = Map.of(
        "3.6", LocalDate.of(2021, 12, 23), "3.7", LocalDate.of(2023, 6, 27), "3.8", LocalDate.of(2024, 10, 7),
        "3.9", LocalDate.of(2025, 10, 31), "3.10", LocalDate.of(2026, 10, 31), "3.11", LocalDate.of(2027, 10, 31),
        "3.12", LocalDate.of(2028, 10, 31), "3.13", LocalDate.of(2029, 10, 31), "3.14", LocalDate.of(2030, 10, 31));

    /** Everything worth saying about the environment, the most serious first. */
    public static List<Finding> findings(PyEnv env) {
        final List<Finding> out = new ArrayList<>();
        // Vulnerabilities of what is installed.
        for (PyEnv.Pkg p : env.packages.values()) {
            if (p.vulnerabilities() == 0) continue;
            final String fixed = minimumFixed(env, p);
            final List<String> ids = new ArrayList<>();
            for (PyPi.Vulnerability v : p.installedRelease.vulnerabilities) ids.add(v.label());
            final String shown = ids.size() <= 5 ? String.join(", ", ids)
                : String.join(", ", ids.subList(0, 5)) + " and " + (ids.size() - 5) + " more";
            out.add(new Finding(Severity.CRITICAL, p.name + " " + p.version + ": " + p.vulnerabilities() + " known vulnerabilit"
                + (p.vulnerabilities() == 1 ? "y" : "ies"), shown + (fixed == null ? "; no fixed version is allowed here"
                : "; all fixed in " + fixed), fixed == null ? Fix.none()
                : new Fix(FixKind.INSTALL, List.of(p.name + "==" + fixed), "Upgrade to " + fixed)));
        }
        // Requirements not met: what pip check says.
        for (PyEnv.Conflict c : env.conflicts) {
            final PyEnv.Pkg dep = env.packages.get(c.dependency());
            if (c.missing()) {
                out.add(new Finding(Severity.HIGH, c.packageName() + " needs " + c.dependency() + ", which is not installed",
                    "requirement: " + c.requirement(), new Fix(FixKind.INSTALL, List.of(requirementSpec(c.requirement())),
                    "Install " + c.dependency())));
            } else {
                final String best = dep == null ? null : bestAllowed(env, c.dependency(), dep.project, List.of());
                out.add(new Finding(Severity.HIGH, c.packageName() + " does not accept " + c.dependency() + " " + c.installed(),
                    "requirement: " + c.requirement() + (best == null ? "" : "; " + best + " satisfies every package"),
                    best == null ? Fix.none() : new Fix(FixKind.INSTALL, List.of(dep.name + "==" + best), "Install " + dep.name + " " + best)));
            }
        }
        // Yanked releases.
        for (PyEnv.Pkg p : env.packages.values()) {
            if (!p.yanked()) continue;
            out.add(new Finding(Severity.MEDIUM, p.name + " " + p.version + " was withdrawn from PyPI (yanked)",
                p.installedRelease.yankedReason.isEmpty() ? "its authors ask not to use it" : p.installedRelease.yankedReason,
                p.safeTarget == null ? Fix.none() : new Fix(FixKind.INSTALL, List.of(p.name + "==" + p.safeTarget), "Upgrade to " + p.safeTarget)));
        }
        // Leftovers of failed installs, and packages installed twice.
        if (!env.leftovers.isEmpty()) {
            out.add(new Finding(Severity.MEDIUM, env.leftovers.size() + " leftover folder" + (env.leftovers.size() == 1 ? "" : "s")
                + " of an interrupted pip run", "folders starting with ~ in site-packages: " + names(env.leftovers)
                + "; pip warns about them on every run", new Fix(FixKind.DELETE_FOLDERS, env.leftovers, "Delete them")));
        }
        for (Map.Entry<String, List<String>> d : env.duplicates.entrySet()) {
            out.add(new Finding(Severity.MEDIUM, d.getKey() + " is installed twice", String.join(" and ", d.getValue()),
                new Fix(FixKind.REINSTALL, List.of(d.getKey()), "Reinstall it")));
        }
        // The interpreter.
        final LocalDate eol = EOL.get(env.minor());
        final LocalDate today = LocalDate.now();
        if (eol != null && eol.isBefore(today)) {
            out.add(new Finding(Severity.HIGH, "Python " + env.minor() + " reached its end of life on " + eol,
                "it receives no security fixes any more; packages drop it one after another", new Fix(FixKind.NEW_VENV, List.of(),
                "New environment with a recent Python")));
        } else if (eol != null && eol.isBefore(today.plusMonths(6))) {
            out.add(new Finding(Severity.LOW, "Python " + env.minor() + " reaches its end of life on " + eol,
                "plan the move to a newer Python", Fix.none()));
        }
        if (env.externallyManaged) {
            out.add(new Finding(Severity.INFO, "This Python is managed by the system (PEP 668)",
                "pip refuses to install into it; use a virtual environment", new Fix(FixKind.NEW_VENV, List.of(), "New venv")));
        } else if (env.isSystem()) {
            out.add(new Finding(Severity.INFO, "Packages go into a " + env.kind() + " Python shared by everything",
                "a virtual environment keeps Sphere's packages apart and lets them be thrown away",
                new Fix(FixKind.NEW_VENV, List.of(), "New venv")));
        }
        // pip itself.
        final PyEnv.Pkg pip = env.packages.get("pip");
        if (pip == null) {
            out.add(new Finding(Severity.HIGH, "pip is not installed", "python -m ensurepip installs it", Fix.none()));
        } else if (pip.outdated()) {
            out.add(new Finding(Severity.LOW, "pip " + pip.version + " can be upgraded to " + pip.latest,
                "newer pips resolve faster and know newer wheels", new Fix(FixKind.INSTALL, List.of("pip==" + pip.latest), "Upgrade pip")));
        }
        // Dependencies nothing needs any more.
        final List<String> orphans = new ArrayList<>();
        for (PyEnv.Pkg p : env.packages.values()) if (p.orphan() && !p.editable) orphans.add(p.name);
        if (!orphans.isEmpty()) {
            out.add(new Finding(Severity.LOW, orphans.size() + " package" + (orphans.size() == 1 ? "" : "s")
                + " installed as dependencies that nothing uses any more", String.join(", ", orphans),
                new Fix(FixKind.UNINSTALL, orphans, "Remove them")));
        }
        // Updates.
        int major = 0;
        int minor = 0;
        int patch = 0;
        int held = 0;
        final List<String> safe = new ArrayList<>();
        for (PyEnv.Pkg p : env.packages.values()) {
            if (!p.outdated()) continue;
            switch (p.jump) {
                case MAJOR -> major++;
                case MINOR -> minor++;
                default -> patch++;
            }
            if (p.safeTarget != null) safe.add(p.name + "==" + p.safeTarget);
            else held++;
        }
        if (major + minor + patch > 0) {
            out.add(new Finding(Severity.INFO, (major + minor + patch) + " updates: " + major + " major, " + minor + " minor, "
                + patch + " patch", held == 0 ? "every update keeps the dependencies satisfied"
                : held + " held back by what other packages require", safe.isEmpty() ? Fix.none()
                : new Fix(FixKind.INSTALL, safe, "Apply the " + safe.size() + " safe updates")));
        }
        out.sort(Comparator.comparing(Finding::severity));
        return out;
    }

    /**
     * 100 for a clean environment; each severity takes its share, the first
     * finding of a kind the most, and no kind more than its cap: five
     * vulnerable packages are worse than one, not five times worse.
     */
    public static int score(List<Finding> findings) {
        final int[] count = new int[Severity.values().length];
        for (Finding f : findings) count[f.severity().ordinal()]++;
        final int[] first = {25, 12, 5, 2, 0};
        final int[] more = {5, 6, 3, 1, 0};
        final int[] cap = {45, 30, 15, 6, 0};
        int s = 100;
        for (int k = 0; k < count.length; k++) {
            if (count[k] == 0) continue;
            s -= Math.min(cap[k], first[k] + more[k] * (count[k] - 1));
        }
        return Math.max(0, s);
    }

    /** The smallest version above the installed one that fixes every vulnerability and is allowed here. */
    static String minimumFixed(PyEnv env, PyEnv.Pkg p) {
        if (p.project == null) return p.safeTarget;
        final Pep440.Version installed = p.parsed();
        Pep440.Version need = null;
        for (PyPi.Vulnerability v : p.installedRelease.vulnerabilities) {
            Pep440.Version smallest = null;
            for (String f : v.fixedIn()) {
                final Pep440.Version fv = Pep440.parse(f);
                if (fv == null || installed != null && fv.compareTo(installed) <= 0) continue;
                if (smallest == null || fv.compareTo(smallest) < 0) smallest = fv;
            }
            if (smallest != null && (need == null || smallest.compareTo(need) > 0)) need = smallest;
        }
        if (need == null) return p.safeTarget;
        final List<String[]> constraints = constraintsOn(env, p.key);
        final List<PyPi.Published> sorted = p.project.sorted(false);
        String best = null;
        for (int i = sorted.size() - 1; i >= 0; i--) {
            final PyPi.Published c = sorted.get(i);
            if (c.parsed().compareTo(need) >= 0 && refusedBy(constraints, c.parsed()) == null) {
                best = c.version();
                break;
            }
        }
        return best != null ? best : need.text;
    }

    /** "numpy>=1.20,<2" from "numpy (>=1.20,<2) ; python_version >= '3.9'". */
    static String requirementSpec(String raw) {
        String r = raw.split(";", 2)[0].strip();
        r = r.replace("(", "").replace(")", "").replace(" ", "");
        return r;
    }

    private static String names(List<String> paths) {
        final List<String> out = new ArrayList<>();
        for (String p : paths) out.add(p.replace('\\', '/').replaceAll(".*/", ""));
        return String.join(", ", out);
    }

    /* ------------------------------------------------------------------ */
    /* Removing a package and what came with it                            */
    /* ------------------------------------------------------------------ */

    /** The packages that need key and would break if it went. */
    public static List<String> dependents(PyEnv env, String key) {
        final PyEnv.Pkg p = env.packages.get(key);
        return p == null ? List.of() : new ArrayList<>(p.requiredBy);
    }

    /**
     * What else can go with a package: its dependencies, and theirs, that
     * were installed as dependencies and that nothing else outside the set
     * would still need (pip has no autoremove; this is one).
     */
    public static List<String> autoremove(PyEnv env, String key) {
        final Set<String> gone = new LinkedHashSet<>();
        gone.add(key);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (String g : new ArrayList<>(gone)) {
                final PyEnv.Pkg p = env.packages.get(g);
                if (p == null) continue;
                for (PyEnv.Req r : p.requires) {
                    if (!r.applies || gone.contains(r.name)) continue;
                    final PyEnv.Pkg dep = env.packages.get(r.name);
                    if (dep == null || dep.requested || dep.editable || PyEnv.TOOLING.contains(dep.key)) continue;
                    if (gone.containsAll(dep.requiredBy)) {
                        gone.add(dep.key);
                        grew = true;
                    }
                }
            }
        }
        gone.remove(key);
        final List<String> out = new ArrayList<>();
        for (String g : gone) out.add(env.packages.get(g).name);
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* Names                                                               */
    /* ------------------------------------------------------------------ */

    /** Widely used packages: what a mistyped name is compared with (and what typosquatters imitate). */
    public static final List<String> POPULAR = List.of(
        "numpy", "scipy", "pandas", "matplotlib", "seaborn", "plotly", "requests", "urllib3", "certifi", "idna",
        "charset-normalizer", "six", "python-dateutil", "pytz", "tzdata", "pyyaml", "setuptools", "wheel", "pip",
        "packaging", "attrs", "jinja2", "markupsafe", "click", "colorama", "tqdm", "rich", "pydantic", "typing-extensions",
        "scikit-learn", "scikit-image", "statsmodels", "sympy", "numba", "llvmlite", "cython", "pillow", "opencv-python",
        "h5py", "tables", "netcdf4", "xarray", "dask", "distributed", "polars", "pyarrow", "fastparquet", "openpyxl",
        "xlrd", "lxml", "beautifulsoup4", "html5lib", "sqlalchemy", "psycopg2", "psycopg2-binary", "pymysql", "redis",
        "boto3", "botocore", "s3transfer", "aiohttp", "httpx", "flask", "django", "fastapi", "uvicorn", "gunicorn",
        "pytest", "pytest-cov", "coverage", "mypy", "black", "ruff", "flake8", "pylint", "isort", "tox", "nox",
        "jupyter", "jupyterlab", "notebook", "ipython", "ipykernel", "ipywidgets", "nbformat", "nbconvert",
        "torch", "torchvision", "tensorflow", "keras", "jax", "jaxlib", "transformers", "tokenizers", "datasets",
        "huggingface-hub", "accelerate", "onnx", "onnxruntime", "xgboost", "lightgbm", "catboost", "optuna",
        "networkx", "shapely", "geopandas", "pyproj", "folium", "bokeh", "altair", "dash", "streamlit",
        "cryptography", "pyopenssl", "paramiko", "pyjwt", "bcrypt", "pycryptodome", "websockets", "pyzmq", "tornado",
        "uproot", "awkward", "hist", "boost-histogram", "mplhep", "vector", "coffea", "iminuit", "pyhf", "zfit",
        "particle", "hepunits", "pylhe", "fastjet", "numexpr", "lmfit", "emcee", "corner", "astropy", "healpy",
        "pyspark", "joblib", "threadpoolctl", "psutil", "docker", "kubernetes", "protobuf", "grpcio", "msgpack", "orjson",
        "ujson", "simplejson", "toml", "tomli", "regex", "pyparsing", "kiwisolver", "cycler", "fonttools", "contourpy");

    /** The edit distance of two names, for "did you mean". */
    public static int distance(String a, String b) {
        final int[] prev = new int[b.length() + 1];
        final int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                final int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                // A swap of two letters counts as one mistake.
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    cur[j] = Math.min(cur[j], prev[j - 1]);
                }
            }
            System.arraycopy(cur, 0, prev, 0, cur.length);
        }
        return prev[b.length()];
    }

    /** Known names close to a typed one (not equal to it): a typo, or a typosquatting package. */
    public static List<String> similar(String name, Collection<String> extra) {
        final String n = Pep440.normalize(name);
        final Set<String> pool = new LinkedHashSet<>(POPULAR);
        for (String e : extra) pool.add(Pep440.normalize(e));
        final List<String> out = new ArrayList<>();
        for (String p : pool) {
            if (p.equals(n)) return List.of();
            final int d = distance(n, p);
            if (d > 0 && d <= (p.length() <= 5 ? 1 : 2)) out.add(p);
        }
        return out;
    }

    /** Import names whose distribution on PyPI is called otherwise. */
    public static final Map<String, String> IMPORT_ALIASES = Map.ofEntries(
        Map.entry("cv2", "opencv-python"), Map.entry("sklearn", "scikit-learn"), Map.entry("skimage", "scikit-image"),
        Map.entry("PIL", "Pillow"), Map.entry("yaml", "PyYAML"), Map.entry("bs4", "beautifulsoup4"),
        Map.entry("dateutil", "python-dateutil"), Map.entry("dotenv", "python-dotenv"), Map.entry("Crypto", "pycryptodome"),
        Map.entry("OpenSSL", "pyOpenSSL"), Map.entry("jwt", "PyJWT"), Map.entry("serial", "pyserial"), Map.entry("usb", "pyusb"),
        Map.entry("magic", "python-magic"), Map.entry("docx", "python-docx"), Map.entry("pptx", "python-pptx"),
        Map.entry("attr", "attrs"), Map.entry("fitz", "PyMuPDF"), Map.entry("zmq", "pyzmq"), Map.entry("gi", "PyGObject"),
        Map.entry("wx", "wxPython"), Map.entry("MySQLdb", "mysqlclient"), Map.entry("psycopg2", "psycopg2-binary"),
        Map.entry("mpl_toolkits", "matplotlib"), Map.entry("Bio", "biopython"), Map.entry("igraph", "python-igraph"),
        Map.entry("git", "GitPython"), Map.entry("github", "PyGithub"), Map.entry("nacl", "PyNaCl"),
        Map.entry("pkg_resources", "setuptools"), Map.entry("IPython", "ipython"), Map.entry("win32api", "pywin32"),
        Map.entry("win32con", "pywin32"), Map.entry("pywintypes", "pywin32"), Map.entry("boost_histogram", "boost-histogram"),
        Map.entry("google", "protobuf"), Map.entry("ruamel", "ruamel.yaml"), Map.entry("Levenshtein", "python-Levenshtein"),
        Map.entry("slugify", "python-slugify"), Map.entry("multipart", "python-multipart"), Map.entry("jose", "python-jose"),
        Map.entry("telegram", "python-telegram-bot"), Map.entry("discord", "discord.py"), Map.entry("kafka", "kafka-python"),
        Map.entry("sentencepiece", "sentencepiece"), Map.entry("tables", "tables"), Map.entry("lal", "lalsuite"));

    /** Modules that are no pip package at all, with what to do instead. */
    public static final Map<String, String> NOT_ON_PIP = Map.of(
        "ROOT", "ROOT comes with ROOT itself (thisroot.sh) or conda install -c conda-forge root",
        "cppyy_backend", "comes with cppyy", "Geant4", "conda install -c conda-forge geant4",
        "tkinter", "comes with the Python installer (tcl/tk option)", "lhapdf", "conda install -c conda-forge lhapdf",
        "pythia8", "conda install -c conda-forge pythia8");

    /** Sets of packages that go together, ready to install. */
    public static final Map<String, List<String>> PRESETS = presets();

    private static Map<String, List<String>> presets() {
        final Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("Scikit-HEP analysis", List.of("uproot", "awkward", "hist", "boost-histogram", "mplhep", "vector", "particle",
            "hepunits", "iminuit"));
        m.put("Statistics & fits (HEP)", List.of("iminuit", "pyhf", "zfit", "numdifftools", "lmfit", "emcee", "corner"));
        m.put("Event generators I/O", List.of("pylhe", "pyhepmc", "particle", "fastjet"));
        m.put("Scientific core", List.of("numpy", "scipy", "pandas", "matplotlib", "sympy"));
        m.put("Jupyter", List.of("jupyterlab", "ipykernel", "ipywidgets", "nbformat"));
        m.put("Machine learning", List.of("scikit-learn", "xgboost", "lightgbm", "torch"));
        m.put("Astro", List.of("astropy", "healpy", "astroquery", "photutils"));
        m.put("Data formats", List.of("h5py", "pyarrow", "tables", "netCDF4", "openpyxl"));
        m.put("Developer tools", List.of("pytest", "ruff", "mypy", "black", "pre-commit"));
        return m;
    }

    /** A size, as people read them. */
    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0);
    }

    /** "3 days ago", "5 months ago". */
    public static String ago(java.time.Instant t) {
        if (t == null) return "";
        final long days = java.time.Duration.between(t, java.time.Instant.now()).toDays();
        if (days < 1) return "today";
        if (days < 2) return "yesterday";
        if (days < 45) return days + " days ago";
        if (days < 540) return (days / 30) + " months ago";
        return (days / 365) + " years ago";
    }
}
