package com.sphere.core.python;

import com.sphere.utils.JsonParser;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class PackageIntelligence {

    private final PythonEnvService env;

    public static class BrokenModule {
        public final String name;
        public final String error;

        public BrokenModule(String name, String error) {
            this.name = name;
            this.error = error;
        }
    }

    public static class Suggestion {
        public final String reason;
        public final String module;

        public Suggestion(String reason, String module) {
            this.reason = reason;
            this.module = module;
        }
    }

    public PackageIntelligence(PythonEnvService env) {
        this.env = env;
    }

    /* -------------------------------------------------------------------------
    *  1) Broken Package
    */
    public List<BrokenModule> findBrokenModules() throws IOException, InterruptedException {

        // Safe Python script that avoids importing GUI or side‑effect modules
        String script =
            "import pkgutil, importlib\n"
        + "import sys\n"
        + "\n"
        + "# Modules known to trigger side effects (GUI, browser, popups, etc.)\n"
        + "IGNORE = {\n"
        + "    'antigravity',      # opens xkcd 353\n"
        + "    'this',             # prints a poem\n"
        + "    'turtle',           # opens a graphics window\n"
        + "    'tkinter',          # GUI\n"
        + "    'pygame',           # GUI\n"
        + "    'matplotlib',       # may open windows\n"
        + "    'matplotlib.pyplot',\n"
        + "    'PyQt5', 'PySide2', 'PySide6', 'PyQt6',\n"
        + "    'cv2',              # OpenCV may open image windows\n"
        + "    'PIL',              # Pillow may open images\n"
        + "}\n"
        + "\n"
        + "# Prefixes of modules that should not be imported\n"
        + "IGNORE_PREFIXES = (\n"
        + "    'tk', 'pygame', 'matplotlib', 'PyQt', 'PySide', 'cv2', 'PIL'\n"
        + ")\n"
        + "\n"
        + "def should_ignore(name):\n"
        + "    if name in IGNORE:\n"
        + "        return True\n"
        + "    return any(name.startswith(p) for p in IGNORE_PREFIXES)\n"
        + "\n"
        + "for m in pkgutil.iter_modules():\n"
        + "    name = m.name\n"
        + "    if should_ignore(name):\n"
        + "        continue\n"
        + "    try:\n"
        + "        importlib.import_module(name)\n"
        + "    except Exception as e:\n"
        + "        print(name + '\\t' + str(e))\n";

        List<String> out = env.exec("-c", script);
        List<BrokenModule> broken = new ArrayList<>();

        for (String line : out) {
            String[] parts = line.split("\\t", 2);
            if (parts.length == 2) {
                broken.add(new BrokenModule(parts[0], parts[1]));
            }
        }
        return broken;
    }

    /* -------------------------------------------------------------------------
    *  2) Unused Package
    */
    public List<String> findUnusedModules(List<String> projectFiles)
            throws IOException, InterruptedException {
        
        List<JsonParser.ModuleInfo> installed = env.listModules();
        Set<String> installedNames = installed.stream()
                .map(m -> m.name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        
        Set<String> used = new HashSet<>();
        for (String path : projectFiles) {
            List<String> lines = env.exec("-c",
                "import io, tokenize\n" +
                "src = open(r'" + path.replace("\\", "\\\\") + "', 'rb').readline\n" +
                "for tok in tokenize.tokenize(src):\n" +
                "    if tok.type == 1 and tok.string == 'import':\n" +
                "        pass\n");
        }

        
        List<String> unused = new ArrayList<>();
        for (String name : installedNames) {
            if (!used.contains(name)) {
                unused.add(name);
            }
        }
        return unused;
    }

    /* -------------------------------------------------------------------------
    *  3) Suggestions simples
    */
    public List<Suggestion> suggestPackages(List<JsonParser.ModuleInfo> installed) {
        Set<String> names = installed.stream()
                .map(m -> m.name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        List<Suggestion> suggestions = new ArrayList<>();

        if (names.contains("numpy") && !names.contains("pandas")) {
            suggestions.add(new Suggestion("numpy is installed", "pandas"));
        }
        if (names.contains("matplotlib") && !names.contains("seaborn")) {
            suggestions.add(new Suggestion("matplotlib is installed", "seaborn"));
        }
        if (names.contains("scipy") && !names.contains("scikit-learn")) {
            suggestions.add(new Suggestion("scipy is installed", "scikit-learn"));
        }

        return suggestions;
    }

    /* -------------------------------------------------------------------------
    *  4) Auto-fix (simple)
    */
    public List<String> autoFixEnvironment() throws IOException, InterruptedException {
        List<String> log = new ArrayList<>();

        // 1) pip check
        log.add("Running: pip check");
        log.addAll(Arrays.asList(env.checkDependencies().split("\\R")));

        // 2) upgrade pip
        log.add("Upgrading pip...");
        log.addAll(env.upgradePip());

        // 3) upgrade outdated
        log.add("Upgrading outdated packages...");
        log.addAll(env.upgradeAllOutdated());

        return log;
    }
}

