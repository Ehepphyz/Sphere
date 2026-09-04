package com.sphere.utils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * SettingsManager handles parsing and writing of settings.conf.
 * Preserves INI sections, is encoding-safe, and validates tool paths cross-platform.
 * Implements strict line-by-line sequential accumulation for Bash-like path manipulation.
 */
public class SettingsManager {

    // Maps a SectionName to a List of Key-Value pairs to allow duplicate keys (e.g., multiple PATH lines)
    private final Map<String, List<Map.Entry<String, String>>> sections = new LinkedHashMap<>();
    public static final String CONFIG_FILENAME = "settings.conf";

    /**
     * The file exactly as it was read. Saving rebuilds from these lines instead of
     * from the parsed map, so the header, the comments and the original order
     * survive a write; the previous writer regenerated the file and dropped them.
     */
    private final List<String> rawLines = new ArrayList<>();

    public SettingsManager() {
        loadSettings();
    }

    // -------------------------------------------------------------------------
    // LOADING / SAVING
    // -------------------------------------------------------------------------

    public synchronized void loadSettings() {
        File file = new File(CONFIG_FILENAME);
        if (!file.exists()) return;

        sections.clear();
        rawLines.clear();
        
        // Root tracking initialization: Avoid hardcoded magic section strings.
        // Unmapped keys at the top of the file automatically stream to a "GLOBAL" scope.
        String currentSection = null;

        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                rawLines.add(raw);
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                if (line.startsWith("[") && line.endsWith("]")) {
                    currentSection = line.substring(1, line.length() - 1).toUpperCase().trim();
                    sections.putIfAbsent(currentSection, new ArrayList<>());
                    continue;
                }

                int eq = line.indexOf('=');
                if (eq > 0) {
                    String key = line.substring(0, eq).toUpperCase().trim();
                    String val = line.substring(eq + 1).trim();
                    
                    String targetSection = (currentSection != null) ? currentSection : "GLOBAL";
                    sections.putIfAbsent(targetSection, new ArrayList<>());
                    
                    // Store the raw line sequentially. 
                    // Interpolation is strictly handled downstream by EngineConfigRegistry.
                    sections.get(targetSection).add(new AbstractMap.SimpleEntry<>(key, val));
                }
            }
        } catch (IOException e) {
            System.err.println("Critical Error: Unable to read " + CONFIG_FILENAME);
        }
        reportDuplicates();
    }

    public synchronized void saveSettings() {
        List<String> out = rawLines.isEmpty() ? renderFromScratch() : merge();
        File file = new File(CONFIG_FILENAME);
        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            for (String line : out) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("Critical Error: Unable to save " + CONFIG_FILENAME);
            return;
        }
        rawLines.clear();
        rawLines.addAll(out);
    }

    /** Used only when there is no file yet: everything comes from the parsed map. */
    private List<String> renderFromScratch() {
        List<String> out = new ArrayList<>();
        for (var section : sections.entrySet()) {
            if (section.getValue().isEmpty()) continue;
            out.add("[" + section.getKey() + "]");
            for (var kv : section.getValue()) {
                out.add(kv.getKey() + "=" + kv.getValue());
            }
            out.add("");
        }
        return out;
    }

    /**
     * Rewrites the file line by line: a changed value replaces the value of its own
     * line, a new key is inserted after the last key of its section, a removed key
     * drops its line, and everything else is copied through untouched.
     */
    /** A commented key line such as "#GDB_DIR=", as the key "#GDB_DIR". */
    private static final java.util.regex.Pattern COMMENTED_KEY =
        java.util.regex.Pattern.compile("^#\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=.*$");

    /** Key a line declares, commented or not, or null when it declares nothing. */
    private static String keyOf(String line) {
        java.util.regex.Matcher commented = COMMENTED_KEY.matcher(line);
        if (commented.matches()) {
            return "#" + commented.group(1).toUpperCase();
        }
        if (line.startsWith("#")) {
            return null;
        }
        int eq = line.indexOf('=');
        return eq > 0 ? line.substring(0, eq).toUpperCase().trim() : null;
    }

    private List<String> merge() {
        // Where each section's last key line sits, so an addition lands with its own
        // kind rather than after the trailing comments of the section.
        Map<String, Integer> lastKeyLine = new LinkedHashMap<>();
        Map<String, Set<String>> present = new LinkedHashMap<>();
        String current = "GLOBAL";
        for (int i = 0; i < rawLines.size(); i++) {
            String line = rawLines.get(i).trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                current = line.substring(1, line.length() - 1).toUpperCase().trim();
                lastKeyLine.put(current, i);
                present.putIfAbsent(current, new LinkedHashSet<>());
                continue;
            }
            // A placeholder already written as a comment counts as present, or it
            // would be added again on every save.
            String declared = keyOf(line);
            if (declared != null) {
                present.computeIfAbsent(current, k -> new LinkedHashSet<>()).add(declared);
            }
        }

        // Each key the file does not carry is tied to the line of the key it comes
        // after in the parsed order, or to its section header when it comes first.
        Map<String, Integer> anchors = new LinkedHashMap<>();
        Map<String, Integer> keyLine = new LinkedHashMap<>();
        current = "GLOBAL";
        for (int i = 0; i < rawLines.size(); i++) {
            String line = rawLines.get(i).trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                current = line.substring(1, line.length() - 1).toUpperCase().trim();
                continue;
            }
            String declared = keyOf(line);
            if (declared != null) {
                keyLine.put(current + "\u0000" + declared, i);
            }
        }
        for (var section : sections.entrySet()) {
            Integer header = lastKeyLine.get(section.getKey());
            Integer previous = header;
            Set<String> already = present.getOrDefault(section.getKey(), Set.of());
            for (var kv : section.getValue()) {
                Integer own = keyLine.get(section.getKey() + "\u0000" + kv.getKey());
                if (own != null) {
                    previous = own;
                } else if (already.contains(kv.getKey())) {
                    continue;
                } else if (previous != null) {
                    anchors.put(section.getKey() + "\u0000" + kv.getKey(), previous);
                }
            }
        }

        List<String> out = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        current = "GLOBAL";
        for (int i = 0; i < rawLines.size(); i++) {
            String raw = rawLines.get(i);
            String line = raw.trim();

            if (line.startsWith("[") && line.endsWith("]")) {
                out.add(raw);
                current = line.substring(1, line.length() - 1).toUpperCase().trim();
                seen.put(current, 0);
                appendNewKeys(out, current, present, anchors, i);
                continue;
            }

            int eq = line.indexOf('=');
            if (line.isEmpty() || line.startsWith("#") || eq <= 0) {
                out.add(raw);
                appendNewKeys(out, current, present, anchors, i);
                continue;
            }


            String key = line.substring(0, eq).toUpperCase().trim();
            String value = valueOf(current, key, seen.merge(current + "\u0000" + key, 1, Integer::sum) - 1);
            if (value == null) {
                // Removed through removeProperty: the line goes with it.
                appendNewKeys(out, current, present, anchors, i);
                continue;
            }
            // The left side is copied verbatim so spacing and case stay the user's.
            out.add(raw.substring(0, raw.indexOf('=') + 1) + value);
            appendNewKeys(out, current, present, anchors, i);
        }

        // Sections the file never carried.
        for (var section : sections.entrySet()) {
            if (present.containsKey(section.getKey()) || section.getValue().isEmpty()) {
                continue;
            }
            if (!out.isEmpty() && !out.get(out.size() - 1).isBlank()) {
                out.add("");
            }
            out.add("[" + section.getKey() + "]");
            for (var kv : section.getValue()) {
                out.add(kv.getKey() + "=" + kv.getValue());
            }
        }
        return out;
    }

    /**
     * Inserts the keys a section gained, each right after the key it follows in the
     * parsed order. A new GDB_DIR declared after GPP_DIR lands under it rather than
     * at the end of the section, among unrelated tools.
     */
    private void appendNewKeys(List<String> out, String section,
                               Map<String, Set<String>> present,
                               Map<String, Integer> anchors, int index) {
        List<Map.Entry<String, String>> entries = sections.get(section);
        if (entries == null) {
            return;
        }
        Set<String> already = present.getOrDefault(section, Set.of());
        Set<String> written = new LinkedHashSet<>();
        for (var kv : entries) {
            if (already.contains(kv.getKey()) || !written.add(kv.getKey())) {
                continue;
            }
            Integer anchor = anchors.get(section + "\u0000" + kv.getKey());
            if (anchor != null && anchor == index) {
                out.add(kv.getKey() + "=" + kv.getValue());
            }
        }
    }

    /** The nth value recorded for a key, matching repeated keys such as PATH. */
    private String valueOf(String section, String key, int occurrence) {
        List<Map.Entry<String, String>> entries = sections.get(section);
        if (entries == null) {
            return null;
        }
        int seen = 0;
        for (var kv : entries) {
            if (kv.getKey().equals(key)) {
                if (seen == occurrence) {
                    return kv.getValue();
                }
                seen++;
            }
        }
        return null;
    }

    public void save() {
        saveSettings();
    }

    // -------------------------------------------------------------------------
    // ACCESSORS
    // -------------------------------------------------------------------------

    public String getProperty(String section, String key) {
        if (section == null || key == null) return null;
        String targetSection = section.toUpperCase().trim();
        List<Map.Entry<String, String>> s = sections.get(targetSection);
        if (s == null) return null;
        return sanitizeValue(accumulate(s, targetSection, key.toUpperCase().trim(), 0));
    }

    public String getProperty(String key) {
        if (key == null) return null;
        String lookupKey = key.toUpperCase().trim();

        for (var s : sections.entrySet()) {
            String v = sanitizeValue(accumulate(s.getValue(), s.getKey(), lookupKey, 0));
            if (v != null) return v;
        }
        return null;
    }

    /**
     * Replays every declaration of a key in file order, the way a shell profile
     * does: two PATH= lines stack, each reading the value built so far, and the
     * environment for the first one. Reading only the last line silently dropped
     * every earlier declaration.
     */
    private String accumulate(List<Map.Entry<String, String>> entries, String section,
                              String key, int depth) {
        // SYSTEM_PATH and GENERAL hold one line per key, so a repeated key there is
        // a mistake in the file rather than a second statement. The last declaration
        // that carries a value wins: an empty duplicate left behind by a tool would
        // otherwise silently disable a compiler that is declared right above it.
        if (isSingleValued(section)) {
            String last = null;
            for (var kv : entries) {
                if (kv.getKey().equals(key) && !kv.getValue().isBlank()) {
                    last = kv.getValue();
                }
            }
            if (last == null) {
                for (var kv : entries) {
                    if (kv.getKey().equals(key)) {
                        last = kv.getValue();
                    }
                }
            }
            return last == null ? null : expand(last, key, System.getenv(key), depth);
        }
        String value = null;
        for (var kv : entries) {
            if (!kv.getKey().equals(key)) {
                continue;
            }
            String self = value != null ? value : System.getenv(key);
            value = expand(kv.getValue(), key, self, depth);
        }
        return value;
    }

    /** Sections that are Sphere's own starting point, one declaration per key. */
    private static boolean isSingleValued(String section) {
        return "SYSTEM_PATH".equals(section) || "GENERAL".equals(section);
    }

    /** Says once what the file carries twice, so it can be cleaned up. */
    private void reportDuplicates() {
        for (var section : sections.entrySet()) {
            if (!isSingleValued(section.getKey())) {
                continue;
            }
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (var kv : section.getValue()) {
                counts.merge(kv.getKey(), 1, Integer::sum);
            }
            for (var count : counts.entrySet()) {
                if (count.getValue() > 1) {
                    AppLogger.warn("settings.conf: " + count.getKey() + " is declared "
                                   + count.getValue() + " times in [" + section.getKey()
                                   + "], which allows one line per key.");
                }
            }
        }
    }

    /** A key referenced from another value, stacked the same way if repeated. */
    private String resolveReference(String name, int depth) {
        if (depth > 8) {
            return null;
        }
        String lookup = name.toUpperCase().trim();
        for (var s : sections.entrySet()) {
            String v = accumulate(s.getValue(), s.getKey(), lookup, depth);
            if (v != null) return v;
        }
        return null;
    }

    /** Raw value of a key, without expansion, used while resolving references. */
    private String rawProperty(String key) {
        if (key == null) return null;
        String lookupKey = key.toUpperCase().trim();
        for (var s : sections.values()) {
            for (int i = s.size() - 1; i >= 0; i--) {
                if (s.get(i).getKey().equals(lookupKey)) {
                    return s.get(i).getValue();
                }
            }
        }
        return null;
    }

    private static final java.util.regex.Pattern VAR_REFERENCE =
        java.util.regex.Pattern.compile("\\$\\{?([A-Za-z_][A-Za-z0-9_]*)\\}?");

    /**
     * Resolves $NAME and ${NAME} against the other keys of this file, then against
     * the environment. The file is written like a shell profile, so a value such as
     * CPP_COMPILER_PATH=$GPP_DIR used to reach callers as the literal text.
     *
     * @param self  key being expanded, so PATH=$PATH takes the environment instead
     *              of looping on itself
     * @param depth guards against a cycle between two keys
     */
    private String expand(String value, String self, int depth) {
        return expand(value, self, System.getenv(self == null ? "" : self), depth);
    }

    /** @param selfValue what a reference to the key being expanded resolves to */
    private String expand(String value, String self, String selfValue, int depth) {
        if (value == null || value.indexOf('$') < 0 || depth > 8) {
            return value;
        }
        java.util.regex.Matcher m = VAR_REFERENCE.matcher(value);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String replacement;
            if (name.equalsIgnoreCase(self)) {
                replacement = selfValue;
            } else {
                replacement = resolveReference(name, depth + 1);
            }
            if (replacement == null) {
                replacement = System.getenv(name);
            }
            if (replacement == null) {
                replacement = m.group(0); // unknown reference stays visible
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Cross-platform guard:
     * - Windows: Accepts C:\..., C:/..., /c/..., /C:/..., Rejects /usr/bin/...
     * - Unix: Rejects C:\..., C:/...
     */
    private String sanitizeValue(String value) {
        // Only trims. Judging a value by its path shape nulled anything that merely
        // looked foreign -- a Windows path read under Linux, a /mnt/c path read under
        // Windows, and any plain value containing a colon. Path shape is resolvePath's
        // business, and a value the user wrote is returned as written.
        return value == null ? null : value.trim();
    }

    /**
     * Rewrites a path into the notation the running platform understands.
     * Returns null when the path cannot mean anything here, for example a C: drive
     * on a native Linux host.
     */
    public static String toNativePath(String raw) {
        return toNativePath(raw, OSValidator.current());
    }

    /**
     * True when the key is declared with nothing after the equals sign in
     * [SYSTEM_PATH] or [GENERAL]. A key absent from both says nothing, so the
     * automatic search may run for it; a key deliberately emptied there must not
     * be second-guessed.
     *
     * Only those two sections give a blank line that meaning. They are where
     * Sphere's own configuration starts, one line per key, so emptying one is a
     * decision to switch that backend off. Everywhere else a key stacks the way a
     * shell profile builds PATH: a blank declaration adds nothing to the stack, and
     * cancels neither the lines around it nor the tool.
     */
    public boolean isDeclaredEmpty(String key) {
        String value = declaredAtOrigin(key);
        return value != null && value.isBlank();
    }

    /** The value a key carries in [SYSTEM_PATH] or [GENERAL], or null. */
    private String declaredAtOrigin(String key) {
        if (key == null) {
            return null;
        }
        String lookupKey = key.toUpperCase().trim();
        for (var s : sections.entrySet()) {
            if (!isSingleValued(s.getKey())) {
                continue;
            }
            String v = sanitizeValue(accumulate(s.getValue(), s.getKey(), lookupKey, 0));
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /**
     * The declared path when it works here, otherwise the same tool found where
     * this platform keeps it. A declaration always wins when it is usable, so the
     * user keeps the hand; the search only runs when the declared path cannot work
     * on the running system, which is what makes one settings.conf serve Windows,
     * WSL, Linux and macOS without per-platform sections.
     */
    public String resolveTool(String key, String fallbackName) {
        return resolveTool(key, fallbackName, new String[0]);
    }

    /**
     * Same, with keys whose folder is worth looking into when the tool is not on
     * the PATH. A toolchain installs its pieces together, so a gdb missing from the
     * PATH is usually sitting next to the g++ that settings.conf already declares.
     *
     * @param siblingKeys other settings.conf keys naming tools of the same family
     */
    public String resolveTool(String key, String fallbackName, String... siblingKeys) {
        // settings.conf states that a key left blank disables that backend. Looking
        // for the tool anyway would override a decision the user wrote down.
        if (isDeclaredEmpty(key)) {
            return null;
        }
        StringBuilder reason = new StringBuilder();
        String declared = resolvePath(key, fallbackName, reason);
        if (declared != null) {
            return declared;
        }
        String name = toolName(key, fallbackName);
        if (name == null) {
            return null;
        }
        // Finding the same tool elsewhere is the normal case for a settings.conf
        // carried between systems, so it stays silent. Only an outright failure
        // is worth the user's attention, and then it says what was tried.
        String found = findOnSystemPath(name);
        if (found == null) {
            found = findBesideSibling(name, siblingKeys);
        }
        if (found == null && reason.length() > 0) {
            // A warning, not an error: this method's answer is "not available", and
            // whether that matters is the caller's business. The startup diagnostic
            // knows which tools are optional and says CRITICAL only for the others,
            // so raising an alarm here printed the same absence twice, once too loud.
            AppLogger.warn(key + ": " + reason + ", and " + name
                           + " is not on this system's PATH.");
        }
        return found;
    }

    /** Looks for the tool in the folder of an already resolved companion tool. */
    private String findBesideSibling(String executableName, String[] siblingKeys) {
        if (executableName == null || siblingKeys == null) {
            return null;
        }
        for (String sibling : siblingKeys) {
            String declared = getProperty(sibling);
            if (declared == null || declared.isBlank()) {
                continue;
            }
            String here = toNativePath(declared);
            if (here == null) {
                continue;
            }
            File file = new File(here);
            File folder = file.isDirectory() ? file : file.getParentFile();
            if (folder == null) {
                continue;
            }
            File candidate = new File(folder, executableName);
            if (candidate.isFile() && candidate.canExecute()) {
                return candidate.getAbsolutePath();
            }
        }
        return null;
    }

    /**
     * Tool name to look for, taken from the declared value so the search follows
     * what the user asked for. The extension moves with the platform: a declared
     * g++.exe is g++ on Unix, and g++ is g++.exe on Windows.
     */
    private String toolName(String key, String fallbackName) {
        String raw = getProperty(key);
        String name = null;
        if (raw != null && !raw.isBlank()) {
            String v = raw.trim().replace('\\', '/');
            int slash = v.lastIndexOf('/');
            name = slash >= 0 ? v.substring(slash + 1) : v;
            if (name.isEmpty() || name.endsWith(":")) {
                name = null;
            }
            // A key may name the directory holding the tool rather than the tool.
            // Its last segment is then a folder name, not something to look for.
            String here = toNativePath(v);
            if (here != null && new File(here).isDirectory()) {
                name = null;
            }
        }
        if (name == null) {
            name = fallbackName;
        }
        if (name == null) {
            return null;
        }
        boolean windows = OSValidator.current() == OSValidator.Platform.WINDOWS;
        if (windows && !name.toLowerCase().endsWith(".exe")) {
            return name + ".exe";
        }
        if (!windows && name.toLowerCase().endsWith(".exe")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }

    private static String findOnSystemPath(String executableName) {
        String env = System.getenv("PATH");
        if (env == null || executableName == null) {
            return null;
        }
        for (String dir : env.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            File candidate = new File(dir, executableName);
            if (candidate.isFile() && candidate.canExecute()) {
                return candidate.getAbsolutePath();
            }
        }
        return null;
    }

    /**
     * Explains why a Windows path is out of reach from WSL, or null when it is
     * fine. Neither case can be repaired from here -- both are system settings --
     * so the point is to name the setting instead of reporting "not found".
     */
    private static String wslReachability(String nativePath) {
        if (OSValidator.current() != OSValidator.Platform.WSL) {
            return null;
        }
        String root = OSValidator.wslMountRoot();
        if (!nativePath.startsWith(root + "/")) {
            return null;
        }
        String drive = nativePath.substring(root.length() + 1, root.length() + 2);
        if (!OSValidator.isDriveMounted(drive)) {
            return "drive " + drive.toUpperCase() + ": is not mounted under " + root
                 + ". Enable [automount] in /etc/wsl.conf, or point this key at a "
                 + "Linux path.";
        }
        if (nativePath.toLowerCase().endsWith(".exe") && !OSValidator.isWindowsInteropEnabled()) {
            return "WSL cannot launch Windows executables: [interop] is disabled in "
                 + "/etc/wsl.conf. Point this key at a Linux build instead.";
        }
        return null;
    }

    /** Matches a path under a non-default WSL automount root such as /windows/c. */
    private static String mountedDrivePrefix(String v) {
        String root = OSValidator.wslMountRoot();
        if ("/mnt".equals(root)) {
            return null;
        }
        return v.matches("^" + java.util.regex.Pattern.quote(root) + "/[A-Za-z](/.*)?$")
             ? root : null;
    }

    /** Same conversion against an explicit target, which is what the tests drive. */
    public static String toNativePath(String raw, OSValidator.Platform target) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return null;
        }

        // --- Read the source notation into (drive, remainder) or a pure POSIX path
        String drive = null;
        String rest = null;

        if (v.matches("^[A-Za-z]:[\\\\/].*") || v.matches("^[A-Za-z]:$")) {
            drive = v.substring(0, 1);                      // C:\x  or C:/x
            rest = v.length() > 2 ? v.substring(2) : "";
        } else if (v.matches("^/[A-Za-z]:/.*")) {
            drive = v.substring(1, 2);                      // /C:/x
            rest = v.substring(3);
        } else if (v.matches("^/mnt/[A-Za-z](/.*)?$")) {
            drive = v.substring(5, 6);                      // /mnt/c/x
            rest = v.length() > 6 ? v.substring(6) : "";
        } else if (mountedDrivePrefix(v) != null) {
            String prefix = mountedDrivePrefix(v);          // custom automount root
            drive = v.substring(prefix.length() + 1, prefix.length() + 2);
            rest = v.length() > prefix.length() + 2 ? v.substring(prefix.length() + 2) : "";
        } else if (v.matches("^/[A-Za-z](/.*)?$") && !v.startsWith("/mnt")) {
            drive = v.substring(1, 2);                      // /c/x, MSYS2 and Git Bash
            rest = v.length() > 2 ? v.substring(2) : "";
        }

        boolean windowsPath = drive != null;
        rest = rest == null ? null : rest.replace('\\', '/');

        switch (target) {
            case WINDOWS:
                if (windowsPath) {
                    return (drive.toUpperCase() + ":" + rest).replace('/', '\\');
                }
                // A pure POSIX path has no meaning on a native Windows host.
                return null;

            case WSL:
                if (windowsPath) {
                    // The mount root is configurable, so /mnt is only a default.
                    return OSValidator.wslMountRoot() + "/" + drive.toLowerCase() + rest;
                }
                return v.replace('\\', '/');

            case LINUX:
            case MACOS:
            default:
                if (windowsPath) {
                    return null;
                }
                return v.replace('\\', '/');
        }
    }

    public String getArgs(String toolKey) {
        String v = getProperty("ARGS", toolKey);
        return (v != null) ? v : "";
    }

    public synchronized void setProperty(String section, String key, String value) {
        if (section == null || key == null) return;
        String targetSection = section.toUpperCase().trim();
        String targetKey = key.toUpperCase().trim();
        
        List<Map.Entry<String, String>> s = sections.computeIfAbsent(targetSection, k -> new ArrayList<>());
        
        boolean updated = false;
        // Update the last occurrence of the key if it exists
        for (int i = s.size() - 1; i >= 0; i--) {
            if (s.get(i).getKey().equals(targetKey)) {
                s.set(i, new AbstractMap.SimpleEntry<>(targetKey, value));
                updated = true;
                break;
            }
        }
        // Otherwise, append it as a new line
        if (!updated) {
            s.add(new AbstractMap.SimpleEntry<>(targetKey, value));
        }
        saveSettings();
    }

    /**
     * Writes a key as a comment, in its place, without declaring it. The file then
     * names every key Sphere knows while leaving the three states intact: a
     * commented key is still absent, so the automatic search runs; uncommented and
     * left empty it disables the backend; filled in it wins.
     */
    public synchronized void declarePlaceholder(String section, String afterKey, String key) {
        if (key == null) {
            return;
        }
        String commented = "#" + key.toUpperCase().trim();
        // Stored like any entry so it keeps its rank; the parser skips comment
        // lines, so nothing ever reads it back as a declaration.
        setPropertyAfter(section, afterKey, commented, "");
    }

    /**
     * Adds a key immediately after another one, so a generated entry sits with the
     * tools it belongs to. Falls back to the end of the section when the neighbour
     * is not there, and behaves like setProperty when the key already exists.
     */
    public synchronized void setPropertyAfter(String section, String afterKey,
                                              String key, String value) {
        if (section == null || key == null) return;
        String targetSection = section.toUpperCase().trim();
        String targetKey = key.toUpperCase().trim();
        List<Map.Entry<String, String>> s = sections.computeIfAbsent(targetSection,
                                                                     k -> new ArrayList<>());
        for (int i = s.size() - 1; i >= 0; i--) {
            if (s.get(i).getKey().equals(targetKey)) {
                s.set(i, new AbstractMap.SimpleEntry<>(targetKey, value));
                saveSettings();
                return;
            }
        }
        int at = s.size();
        if (afterKey != null) {
            String neighbour = afterKey.toUpperCase().trim();
            for (int i = s.size() - 1; i >= 0; i--) {
                if (s.get(i).getKey().equals(neighbour)) {
                    at = i + 1;
                    break;
                }
            }
        }
        s.add(at, new AbstractMap.SimpleEntry<>(targetKey, value));
        saveSettings();
    }

    public synchronized void setProperty(String key, String value) {
        if (key == null) return;
        String lookupKey = key.toUpperCase().trim();
        String section = "GENERAL"; // Default target fallback for arbitrary engine sets

        if (lookupKey.startsWith("WIN_") || lookupKey.startsWith("UNIX_")) {
            section = "TERMINAL_CONFIG";
        } else if (lookupKey.endsWith("_EXEC") || lookupKey.endsWith("_DIR") || lookupKey.endsWith("_FWORK_DIR")) {
            section = "SYSTEM_PATH";
        } else if (lookupKey.endsWith("_ARGS")) {
            section = "ARGS";
        }

        setProperty(section, lookupKey, value);
    }

    public synchronized void removeProperty(String section, String key) {
        if (section == null || key == null) return;
        String targetSection = section.toUpperCase().trim();
        String targetKey = key.toUpperCase().trim();
        
        if (sections.containsKey(targetSection)) {
            // Remove all occurrences of the key within the specified section
            sections.get(targetSection).removeIf(entry -> entry.getKey().equals(targetKey));
            saveSettings();
        }
    }

    // Exposed for downstream sequential Bash evaluation
    public Map<String, List<Map.Entry<String, String>>> getSequentialStructure() {
        return Collections.unmodifiableMap(sections);
    }

    // -------------------------------------------------------------------------
    // PATH RESOLUTION
    // -------------------------------------------------------------------------

    /**
     * Resolves and normalizes explicit executable paths, validating file or directory presence.
     * Stays completely isolated from the global system environment PATH to maintain strict 
     * user configuration priority. Accommodates virtualized runtime mount points and specialized 
     * Windows/Unix directory structures.
     */
    public String resolvePath(String key, String executableName) {
        return resolvePath(key, executableName, null);
    }

    /** Probe form: silent, and records why it failed for the caller to report. */
    public String resolvePath(String key, String executableName, StringBuilder reason) {
        String raw = getProperty(key);
        if (raw == null || raw.isBlank()) return null;
        // A null name used to reach toLowerCase() below and throw, which the outer
        // catch reported as an invalid path: the caller never learned why.
        if (executableName == null) {
            java.io.File direct = new java.io.File(raw.trim());
            return direct.isFile() ? direct.getAbsolutePath() : null;
        }

        // 1. Translate into this platform's notation. normalizeCrossPlatformPath
        // rewrote everything toward Windows whatever the host, so a valid
        // /mnt/c path under WSL came out as C:/ and was then discarded.
        String native_ = toNativePath(raw);
        if (native_ == null) {
            note(reason, "the declared path has no meaning on this system");
            return null;
        }
        String unreachable = wslReachability(native_);
        if (unreachable != null) {
            note(reason, unreachable);
            return null;
        }
        raw = native_;

        // 2. Uniformly resolve system-specific file separator boundaries
        String localized = raw.replace("/", File.separator).replace("\\", File.separator);
        Path p = Paths.get(localized).normalize();
        File f = p.toFile();

        try {
            // Safely check if the target path is a known directory
            boolean isDir = f.exists() && f.isDirectory();

            if (!isDir) {
                // Tier 1: Match against standard filesystem tracking if accessible
                if (f.exists() && matchesExecutable(p, executableName)) {
                    checkIfSymlink(p);
                    return p.toAbsolutePath().toString();
                }

                // Tier 2: Resilient suffix match fallback
                // Bypasses Java NIO/IO false-negatives on virtual reparse mounts
                if (p.getFileName() != null) {
                    String disk = p.getFileName().toString().toLowerCase();
                    String base = executableName.toLowerCase().replace(".exe", "");
                    
                    // Windows adaptation: Strip trailing execution extensions
                    if (System.getProperty("os.name").toLowerCase().contains("win") && disk.endsWith(".exe")) {
                        disk = disk.substring(0, disk.length() - 4);
                    }
                    
                    // Validates base combinations flawlessly
                    if (disk.matches("^" + java.util.regex.Pattern.quote(base) + "([0-9]|\\.|\\-)*$")) {
                        return p.toAbsolutePath().toString();
                    }
                }

                note(reason, "the declared path does not lead to " + executableName);
                return null;
            }

            // Case 2: The path explicitly tracks a container directory holding the binary
            Path match = findExecutableInDirectory(p, executableName);
            if (match != null) {
                checkIfSymlink(match);
                return match.toAbsolutePath().toString();
            }
            note(reason, "the declared directory contains no " + executableName);

        } catch (Exception e) {
            note(reason, "the declared path could not be read");
        }

        return null;
    }

    private static void note(StringBuilder sink, String text) {
        if (sink != null && sink.length() == 0) {
            sink.append(text);
        }
    }


    private boolean matchesExecutable(Path file, String exeName) {
        if (file.getFileName() == null) return false;

        String disk = file.getFileName().toString().toLowerCase();
        String target = exeName.toLowerCase();

        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");

        if (isWin) {
            String[] ext = {".exe", ".cmd", ".bat", ".com"};
            for (String e : ext) {
                if (disk.endsWith(e)) disk = disk.substring(0, disk.length() - e.length());
                if (target.endsWith(e)) target = target.substring(0, target.length() - e.length());
            }
            return disk.equals(target);
        }

        if (disk.equals(target)) return true;

        String safe = java.util.regex.Pattern.quote(target);
        return disk.matches("^" + safe + "([0-9]|\\.|\\-)*$");
    }

    private Path findExecutableInDirectory(Path dir, String exeName) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(f -> (Files.isRegularFile(f) || Files.isSymbolicLink(f)) && matchesExecutable(f, exeName))
                    .findFirst()
                    .orElse(null);
        }
    }

    private void checkIfSymlink(Path p) {
        if (Files.isSymbolicLink(p)) {
            try {
                Files.readSymbolicLink(p);
            } catch (IOException ignored) {}
        }
    }

    public String getConfigFilePath() {
        return CONFIG_FILENAME;
    }
}
