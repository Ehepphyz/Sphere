package com.sphere.components.terminal;

import com.sphere.utils.SettingsManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Terminal configuration, read through SettingsManager so the terminal obeys the
 * same settings.conf as the rest of Sphere: $VAR expansion, the cross-platform
 * path notations, and one single reader.
 *
 * It used to parse the file itself, and looked for it next to the compiled
 * classes rather than next to the application, so from an IDE it read nothing at
 * all and every shell came from a hard-coded path.
 *
 * Keys in [TERMINAL_CONFIG] carry the OS prefix WIN_ or UNIX_; the other system
 * is filtered out. A key left empty is skipped, as it always was.
 */
public final class ConfigLoader {

    private static final Map<String, String> terminalPaths = new LinkedHashMap<>();
    private static final Map<String, String> childEnvironment = new LinkedHashMap<>();

    /** Names the shell owns; overwriting them from settings.conf breaks the session. */
    private static final Set<String> RESERVED = Set.of(
        "PATH", "HOME", "USER", "LOGNAME", "SHELL", "PWD", "OLDPWD", "TMPDIR",
        "TEMP", "TMP", "HOSTNAME", "TERM", "USERPROFILE", "COMSPEC", "SYSTEMROOT");

    private ConfigLoader() { }

    public static void load() {
        load(new SettingsManager());
    }

    public static synchronized void load(SettingsManager settings) {
        terminalPaths.clear();
        childEnvironment.clear();
        if (settings == null) {
            return;
        }

        String prefix = isWindows() ? "WIN_" : "UNIX_";
        Map<String, List<Map.Entry<String, String>>> structure = settings.getSequentialStructure();

        List<Map.Entry<String, String>> section = structure.get("TERMINAL_CONFIG");
        if (section != null) {
            for (Map.Entry<String, String> declared : section) {
                String key = declared.getKey();
                if (!key.startsWith(prefix)) {
                    continue;
                }
                String value = settings.getProperty("TERMINAL_CONFIG", key);
                if (value == null || value.isBlank()) {
                    continue;
                }
                String here = SettingsManager.toNativePath(value);
                terminalPaths.put(key, here == null ? value : here);
            }
        }
        buildEnvironment(settings, structure);
    }

    /** The declared path for a key, already expanded and in this platform's notation. */
    public static synchronized String getPath(String key) {
        return key == null ? null : terminalPaths.get(key.toUpperCase(Locale.ROOT).trim());
    }

    /** Every shell settings.conf declares for this system, in file order. */
    public static synchronized Map<String, String> declaredShells() {
        return new LinkedHashMap<>(terminalPaths);
    }

    /**
     * What the shell inherits on top of the system environment: the PATH
     * settings.conf builds, and the tool roots it declares. This is what makes a
     * command typed in the terminal find the same compiler Sphere just used.
     */
    public static synchronized Map<String, String> environment() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(childEnvironment));
    }

    private static void buildEnvironment(SettingsManager settings,
                                         Map<String, List<Map.Entry<String, String>>> structure) {
        // PATH first. settings.conf stacks it the way a shell profile does, and the
        // expansion has already folded the system PATH into the result.
        List<String> path = new ArrayList<>();
        String declaredPath = settings.getProperty("PATH");
        if (declaredPath != null && !declaredPath.isBlank()) {
            Collections.addAll(path, declaredPath.split(File.pathSeparator));
        }

        // The folders holding the tools Sphere resolved, ahead of the rest, so the
        // terminal and Sphere agree on which g++ the word "g++" means.
        List<String> roots = new ArrayList<>();
        for (String sectionName : new String[]{"SYSTEM_PATH", "GENERAL"}) {
            List<Map.Entry<String, String>> section = structure.get(sectionName);
            if (section == null) {
                continue;
            }
            for (Map.Entry<String, String> declared : section) {
                String key = declared.getKey();
                String value = settings.getProperty(sectionName, key);
                if (value == null || value.isBlank()) {
                    continue;
                }
                String here = SettingsManager.toNativePath(value);
                if (here == null) {
                    continue;
                }
                File file = new File(here);
                if (file.isFile()) {
                    File folder = file.getParentFile();
                    if (folder != null) {
                        roots.add(folder.getAbsolutePath());
                    }
                } else if (file.isDirectory()) {
                    roots.add(file.getAbsolutePath());
                }
                // The key itself travels: a settings.conf naming ROOT_DIR or
                // GEANT4_DIR is what the user then types as $ROOT_DIR.
                if (!RESERVED.contains(key) && key.matches("[A-Z_][A-Z0-9_]*")) {
                    childEnvironment.put(key, here);
                }
            }
        }

        Set<String> merged = new LinkedHashSet<>(roots);
        merged.addAll(path);
        if (!merged.isEmpty()) {
            childEnvironment.put("PATH", String.join(File.pathSeparator, merged));
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }
}
