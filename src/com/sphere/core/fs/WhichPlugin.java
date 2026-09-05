package com.sphere.core.fs;

import com.sphere.core.CommandRouter;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * :which and :env, answering for Sphere rather than for the shell.
 *
 * The Unix which reports the first match on the PATH. The useful question here
 * is different: which binary would Sphere actually run, once the three state
 * rule in settings.conf has had its say. A declared path that works wins, a key
 * left blank disables the tool, and only an absent key triggers a search.
 */
public class WhichPlugin implements CommandRouter.CommandPlugin {

    /** Tool name as typed, mapped to the settings.conf key that governs it. */
    private static final Map<String, String> KEY_BY_NAME = new LinkedHashMap<>();
    static {
        KEY_BY_NAME.put("python", "PYTHON_EXEC");
        KEY_BY_NAME.put("python3", "PYTHON_EXEC");
        KEY_BY_NAME.put("g++", "GPP_DIR");
        KEY_BY_NAME.put("gpp", "GPP_DIR");
        KEY_BY_NAME.put("gcc", "GCC_DIR");
        KEY_BY_NAME.put("gdb", "GDB_DIR");
        KEY_BY_NAME.put("clang", "CLANG_DIR");
        KEY_BY_NAME.put("clangd", "CLANGD_EXEC");
        KEY_BY_NAME.put("clang-format", "CLANG_FORMAT_EXEC");
        KEY_BY_NAME.put("cmake", "CMAKE_DIR");
        KEY_BY_NAME.put("make", "MAKE_DIR");
        KEY_BY_NAME.put("node", "NODE_DIR");
        KEY_BY_NAME.put("julia", "JULIA_DIR");
        KEY_BY_NAME.put("root", "ROOT_DIR");
        KEY_BY_NAME.put("geant4", "GEANT4_DIR");
    }

    private final CommandRouter router;

    public WhichPlugin(CommandRouter router) {
        this.router = router;
    }

    @Override
    public String getName() {
        return "which";
    }

    @Override
    public boolean supports(String input) {
        if (input == null) return false;
        String t = input.trim();
        if (t.startsWith("::")) return false;
        return t.equals(":which") || t.startsWith(":which ")
            || t.equals(":env") || t.startsWith(":env ");
    }

    @Override
    public void execute(String input) {
        List<String> tokens = FsSupport.tokenize(input.trim());
        if (tokens.get(0).equals(":env")) {
            reportEnvironment(tokens);
            return;
        }

        boolean showAll = false;
        List<String> names = new ArrayList<>();
        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equals("--help")) { usageWhich(); return; }
            if (t.equals("--all") || t.equals("-a")) { showAll = true; continue; }
            names.add(t);
        }

        SettingsManager settings = new SettingsManager();

        if (names.isEmpty()) {
            AppLogger.raw(String.format("  %-16s %-12s %s", "tool", "settings.conf", "Sphere would run"));
            for (Map.Entry<String, String> entry : KEY_BY_NAME.entrySet()) {
                if (entry.getKey().equals("python3") || entry.getKey().equals("gpp")) continue;
                report(settings, entry.getKey(), entry.getValue(), false);
            }
            return;
        }

        for (String name : names) {
            String key = KEY_BY_NAME.get(name.toLowerCase(Locale.ROOT));
            report(settings, name, key, showAll);
        }
    }

    private void report(SettingsManager settings, String name, String key, boolean showAll) {
        if (key == null) {
            // Not a tool settings.conf governs: fall back to a plain PATH search
            List<Path> onPath = searchPath(name);
            if (onPath.isEmpty()) {
                AppLogger.raw(String.format("  %-16s %-12s %s", name, "-", "not on PATH"));
            } else {
                AppLogger.raw(String.format("  %-16s %-12s %s", name, "not governed",
                        onPath.get(0)));
                if (showAll) {
                    for (int i = 1; i < onPath.size(); i++) {
                        AppLogger.raw(String.format("  %-16s %-12s %s", "", "", onPath.get(i)));
                    }
                }
            }
            return;
        }

        String state;
        String answer;
        if (settings.isDeclaredEmpty(key)) {
            state = "disabled";
            answer = "nothing -- " + key + " is deliberately blank";
        } else {
            String declared = settings.getProperty(key);
            String resolved = settings.resolveTool(key, name);
            if (resolved == null) {
                state = declared == null || declared.isBlank() ? "not set" : "declared";
                answer = "not found on this system";
            } else if (declared != null && !declared.isBlank()
                       && samePath(declared, resolved)) {
                state = "declared";
                answer = resolved;
            } else if (declared != null && !declared.isBlank()) {
                state = "declared";
                answer = resolved + "   (the declared " + declared + " does not work here)";
            } else {
                state = "not set";
                answer = resolved + "   (found by search)";
            }
        }
        AppLogger.raw(String.format("  %-16s %-12s %s", name, state, answer));

        if (showAll) {
            List<Path> onPath = searchPath(name);
            for (Path p : onPath) {
                AppLogger.raw(String.format("  %-16s %-12s %s", "", "also on PATH", p));
            }
        }
    }

    private static boolean samePath(String declared, String resolved) {
        try {
            return Paths.get(declared).toAbsolutePath().normalize()
                   .equals(Paths.get(resolved).toAbsolutePath().normalize());
        } catch (InvalidPathException bad) {
            return declared.equals(resolved);
        }
    }

    /** Every match on the PATH, in order, with the Windows executable suffixes. */
    private static List<Path> searchPath(String name) {
        List<Path> found = new ArrayList<>();
        String pathVariable = System.getenv("PATH");
        if (pathVariable == null || pathVariable.isBlank()) return found;

        boolean windows = System.getProperty("os.name", "")
                                .toLowerCase(Locale.ROOT).contains("win");
        List<String> candidates = new ArrayList<>();
        candidates.add(name);
        if (windows && !name.contains(".")) {
            for (String suffix : new String[]{".exe", ".bat", ".cmd", ".com"}) {
                candidates.add(name + suffix);
            }
        }

        for (String entry : pathVariable.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) continue;
            for (String candidate : candidates) {
                try {
                    Path probe = Paths.get(entry, candidate);
                    if (Files.isRegularFile(probe) && Files.isExecutable(probe)) {
                        found.add(probe.toAbsolutePath().normalize());
                    }
                } catch (InvalidPathException skip) {
                    // A malformed PATH entry is simply not searched
                }
            }
        }
        return found;
    }

    private void reportEnvironment(List<String> tokens) {
        if (tokens.size() > 1 && tokens.get(1).equals("--help")) {
            AppLogger.raw("Usage: :env [name]");
            AppLogger.raw("Without a name, the variables that decide where Sphere finds its tools.");
            return;
        }

        if (tokens.size() > 1) {
            String name = tokens.get(1);
            String value = System.getenv(name);
            if (value == null) {
                AppLogger.raw("  " + name + " is not set");
            } else if (name.equalsIgnoreCase("PATH")
                    || name.toUpperCase(Locale.ROOT).endsWith("_PATH")) {
                AppLogger.raw("  " + name);
                for (String part : value.split(java.io.File.pathSeparator)) {
                    if (!part.isBlank()) AppLogger.raw("      " + part);
                }
            } else {
                AppLogger.raw("  " + name + " = " + value);
            }
            return;
        }

        AppLogger.raw("  working directory   " + router.getCurrentDirectory());
        AppLogger.raw("  home                " + System.getProperty("user.home"));
        AppLogger.raw("  platform            " + System.getProperty("os.name")
                      + " " + System.getProperty("os.arch"));
        AppLogger.raw("  java                " + System.getProperty("java.version"));
        AppLogger.raw("  file encoding       " + System.getProperty("file.encoding"));

        Path conf = Paths.get("settings.conf").toAbsolutePath();
        AppLogger.raw("  settings.conf       " + conf
                      + (Files.exists(conf) ? "" : "   (not there yet)"));

        for (String name : new String[]{"PATH", "LD_LIBRARY_PATH", "DYLD_LIBRARY_PATH",
                                        "PYTHONPATH", "ROOTSYS", "G4INSTALL"}) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) continue;
            AppLogger.raw("  " + name);
            for (String part : value.split(java.io.File.pathSeparator)) {
                if (!part.isBlank()) AppLogger.raw("      " + part);
            }
        }
    }

    private void usageWhich() {
        AppLogger.raw("Usage: :which [-a] [tool...]");
        AppLogger.raw("Options:");
        AppLogger.raw("  -a            also list every match on the PATH");
        AppLogger.raw("  --help        show this help");
        AppLogger.raw("Without a tool, every tool settings.conf governs is listed.");
        AppLogger.raw("The answer is what Sphere would run, not what a shell would find.");
    }
}
