package com.sphere.core.python.env;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A Python environment as one probe sees it: the interpreter, every
 * distribution installed with what it requires, who requires it, its size,
 * whether the user asked for it or it came as a dependency; and what is
 * wrong with it as pip check would say, plus what pip check does not see
 * (leftovers of failed installs, the same package installed twice).
 *
 * What PyPI knows (latest version, vulnerabilities) is added to the packages
 * later by PyPi and PyAdvisor; the probe itself never touches the network.
 */
public final class PyEnv {

    /** The interpreter. */
    public String executable = "";
    public String version = "";
    public String releaseLevel = "final";
    public String implementation = "cpython";
    public int bits = 64;
    public String platform = "";
    public String prefix = "";
    public String basePrefix = "";
    public boolean conda;
    public Map<String, String> pyvenv = new LinkedHashMap<>();
    public String pip;
    public boolean externallyManaged;
    public String purelib = "";
    public String userSite = "";
    public boolean userSiteEnabled;
    public final Set<String> stdlib = new TreeSet<>();
    /** Import name to the distributions that provide it (importlib.metadata.packages_distributions). */
    public final Map<String, List<String>> imports = new LinkedHashMap<>();

    /** Packages by normalized name. */
    public final Map<String, Pkg> packages = new LinkedHashMap<>();
    public final List<Conflict> conflicts = new ArrayList<>();
    /** Folders whose name starts with ~: what a failed pip leaves behind on Windows. */
    public final List<String> leftovers = new ArrayList<>();
    /** A package found twice, with the places of its metadata. */
    public final Map<String, List<String>> duplicates = new LinkedHashMap<>();

    /** How long the probe took, in milliseconds. */
    public long probeMillis;

    /** One requirement of a package. */
    public static final class Req {
        public String raw = "";
        public String name = "";
        public String spec = "";
        public List<String> extras = new ArrayList<>();
        /** False when its environment marker excludes it here (another platform, an extra). */
        public boolean applies = true;
    }

    /** One installed distribution. */
    public static final class Pkg {
        public String name = "";
        public String key = "";
        public String version = "";
        public String summary = "";
        public String path = "";
        public String installer = "";
        /** Installed because the user asked for it (pip writes REQUESTED), not as a dependency. */
        public boolean requested;
        public boolean editable;
        public long size;
        public int files;
        public List<String> top = new ArrayList<>();
        public List<Req> requires = new ArrayList<>();
        public String requiresPython = "";
        public String license = "";
        public String home = "";
        /** Who requires it, by normalized name; filled by link(). */
        public final List<String> requiredBy = new ArrayList<>();

        // What PyPI says, added by PyAdvisor.
        public PyPi.Project project;
        public PyPi.Release installedRelease;
        public String latest;
        public String safeTarget;
        public String safeReason = "";
        public Pep440.Jump jump = Pep440.Jump.NONE;
        public boolean notOnPyPi;

        public Pep440.Version parsed() {
            return Pep440.parse(version);
        }

        public int vulnerabilities() {
            return installedRelease == null ? 0 : installedRelease.vulnerabilities.size();
        }

        public boolean yanked() {
            return installedRelease != null && installedRelease.yanked;
        }

        public boolean outdated() {
            return latest != null && jump != Pep440.Jump.NONE && jump != Pep440.Jump.DOWNGRADE;
        }

        /** A dependency nobody depends on any more: left over by an uninstall. */
        public boolean orphan() {
            return !requested && requiredBy.isEmpty() && !PyEnv.TOOLING.contains(key);
        }
    }

    /** A requirement not met: missing, or installed at a version it does not accept. */
    public record Conflict(String packageName, String requirement, String dependency, String installed) {
        public boolean missing() {
            return installed == null;
        }
    }

    /** The packages every environment has, never called orphans nor removed by a restore. */
    public static final Set<String> TOOLING = Set.of("pip", "setuptools", "wheel", "uv", "distribute", "pkg-resources");

    /** Who requires whom, from the requirements that apply here. */
    void link() {
        for (Pkg p : packages.values()) p.requiredBy.clear();
        for (Pkg p : packages.values()) {
            for (Req r : p.requires) {
                if (!r.applies || r.name.isEmpty()) continue;
                final Pkg dep = packages.get(r.name);
                if (dep != null && !dep.requiredBy.contains(p.key)) dep.requiredBy.add(p.key);
            }
        }
    }

    /**
     * What pip check reports, found here with Pep440: each requirement that
     * applies on this interpreter, missing or not accepting the installed version.
     */
    void findConflicts() {
        conflicts.clear();
        for (Pkg p : packages.values()) {
            for (Req r : p.requires) {
                if (!r.applies || r.name.isEmpty()) continue;
                final Pkg dep = packages.get(r.name);
                if (dep == null) {
                    conflicts.add(new Conflict(p.name, r.raw, r.name, null));
                    continue;
                }
                if (r.spec.isEmpty()) continue;
                final Pep440.Version v = dep.parsed();
                if (v != null && !Pep440.specifier(r.spec).contains(v, true)) {
                    conflicts.add(new Conflict(p.name, r.raw, r.name, dep.version));
                }
            }
        }
    }

    /** What kind of environment the interpreter lives in, from its files rather than from Sphere's own variables. */
    public String kind() {
        final String exe = executable.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        if (exe.contains("/.pixi/")) return "pixi";
        if (conda) {
            if (exe.contains("micromamba") || exe.contains("/mamba")) return "micromamba";
            return prefix.equals(basePrefix) ? "conda (base)" : "conda";
        }
        if (!pyvenv.isEmpty() || !prefix.equals(basePrefix)) {
            if (pyvenv.containsKey("uv")) return "uv venv";
            if (exe.contains("pypoetry/virtualenvs")) return "poetry venv";
            if (pyvenv.containsKey("virtualenv")) return "virtualenv";
            return "venv";
        }
        if (exe.contains("/.pyenv/") || exe.contains("/pyenv-win/")) return "pyenv";
        if (exe.contains("/windowsapps/")) return "Microsoft Store";
        if (exe.contains("/msys64/") || exe.contains("/mingw")) return "MSYS2";
        if (exe.contains("/uv/python/")) return "uv-managed";
        return "system";
    }

    /** Whether installing here touches a Python shared by the whole system. */
    public boolean isSystem() {
        final String k = kind();
        return k.equals("system") || k.equals("Microsoft Store") || k.equals("MSYS2") || k.equals("conda (base)");
    }

    public long totalSize() {
        long s = 0;
        for (Pkg p : packages.values()) s += p.size;
        return s;
    }

    /** The major.minor of the interpreter, as "3.12". */
    public String minor() {
        final String[] p = version.split("\\.");
        return p.length >= 2 ? p[0] + "." + p[1] : version;
    }
}
