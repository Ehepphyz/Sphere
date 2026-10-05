package com.sphere.core.python.env;

import com.sphere.utils.AppLogger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The manager's findings in the console, for those who never leave it:
 * :py doctor (health and fixes), :py outdated (updates and the safe target
 * of each), :py audit (known vulnerabilities). Same probe, same PyPI cache
 * as the window: a second or two.
 */
public final class PyConsole {

    private PyConsole() {
    }

    /** Sphere's Python, as settings.conf names it. */
    static String python() {
        final String configured = com.sphere.core.python.PythonEnvService.loadPythonExecFromConfig("settings.conf");
        if (configured != null && !configured.isBlank()) return configured;
        final String resolved = new com.sphere.utils.SettingsManager().resolveTool("PYTHON_EXEC",
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "python.exe" : "python3");
        return resolved == null ? "python" : resolved;
    }

    private static PyEnv load(boolean pypi) throws Exception {
        final PyEnv env = PyProbe.run(python());
        if (pypi) PyAdvisor.enrichNow(env, PyPi.get(), false, p -> { });
        return env;
    }

    public static void doctor() {
        Thread.ofVirtual().start(() -> {
            try {
                final long t0 = System.nanoTime();
                final PyEnv env = load(true);
                final List<PyAdvisor.Finding> findings = PyAdvisor.findings(env);
                AppLogger.info(String.format(Locale.ROOT, "[py doctor] Python %s · %s · %d packages · %s · health %d/100 (%.1f s)",
                    env.version, env.kind(), env.packages.size(), PyAdvisor.size(env.totalSize()), PyAdvisor.score(findings),
                    (System.nanoTime() - t0) / 1e9));
                if (findings.isEmpty()) AppLogger.success("[py doctor] nothing to report");
                for (PyAdvisor.Finding f : findings) {
                    final String line = "[" + f.severity() + "] " + f.title() + " — " + f.detail()
                        + (f.fix().kind() == PyAdvisor.FixKind.NONE ? "" : "   → " + f.fix().label());
                    switch (f.severity()) {
                        case CRITICAL, HIGH -> AppLogger.error(line);
                        case MEDIUM -> AppLogger.warn(line);
                        default -> AppLogger.info(line);
                    }
                }
                AppLogger.info("[py doctor] :py settings opens the manager, where each fix is one click (previewed, undoable)");
            } catch (Exception e) {
                AppLogger.error("[py doctor] " + e.getMessage());
            }
        });
    }

    public static void outdated() {
        Thread.ofVirtual().start(() -> {
            try {
                final PyEnv env = load(true);
                final List<PyEnv.Pkg> out = new ArrayList<>();
                for (PyEnv.Pkg p : env.packages.values()) if (p.outdated()) out.add(p);
                out.sort(Comparator.comparing((PyEnv.Pkg p) -> p.jump).reversed().thenComparing(p -> p.name.toLowerCase(Locale.ROOT)));
                AppLogger.info("[py outdated] " + out.size() + " update" + (out.size() == 1 ? "" : "s") + " for " + env.executable);
                for (PyEnv.Pkg p : out) {
                    AppLogger.info(String.format(Locale.ROOT, "  %-26s %-12s → %-12s %-6s %s", p.name, p.version, p.latest,
                        p.jump.name().toLowerCase(Locale.ROOT), p.safeTarget == null ? "held back: " + p.safeReason
                            : p.safeTarget.equals(p.latest) ? "safe" : "safe up to " + p.safeTarget));
                }
            } catch (Exception e) {
                AppLogger.error("[py outdated] " + e.getMessage());
            }
        });
    }

    public static void audit() {
        Thread.ofVirtual().start(() -> {
            try {
                final PyEnv env = load(true);
                int n = 0;
                for (PyEnv.Pkg p : env.packages.values()) {
                    if (p.vulnerabilities() == 0) continue;
                    n++;
                    AppLogger.error(String.format(Locale.ROOT, "[py audit] %s %s: %d known vulnerabilit%s, fixed in %s", p.name,
                        p.version, p.vulnerabilities(), p.vulnerabilities() == 1 ? "y" : "ies", PyAdvisor.minimumFixed(env, p)));
                    for (PyPi.Vulnerability v : p.installedRelease.vulnerabilities) {
                        AppLogger.info("    " + v.label() + "  " + v.summary());
                    }
                }
                if (n == 0) AppLogger.success("[py audit] no known vulnerability in " + env.packages.size() + " packages");
            } catch (Exception e) {
                AppLogger.error("[py audit] " + e.getMessage());
            }
        });
    }
}
