package com.sphere.utils.settingsmanager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory picture of settings.conf, kept close enough to the file to write it
 * back without damage.
 *
 * A category is an ordered list rather than a map: the file reads like a shell
 * profile, where two PATH= lines are two statements that both count. A map kept
 * only the last one, so opening the file in the editor and saving it silently
 * dropped the others.
 */
public final class SettingsFile {

    /** One declaration. Mutable because the editor changes it in place. */
    public static final class Entry {
        private String key;
        private String value;
        /** Line it came from, or -1 when the editor added it. */
        private final int sourceLine;

        public Entry(String key, String value) {
            this(key, value, -1);
        }

        Entry(String key, String value, int sourceLine) {
            this.key = key;
            this.value = value;
            this.sourceLine = sourceLine;
        }

        public String key() { return key; }
        public String value() { return value; }
        public void setKey(String key) { this.key = key; }
        public void setValue(String value) { this.value = value; }
        int sourceLine() { return sourceLine; }
    }

    private final Map<String, List<Entry>> categories = new LinkedHashMap<>();
    /** The file as it was read, so a save keeps its comments and its layout. */
    private final List<String> rawLines = new ArrayList<>();
    /** Name in the file, for the categories the editor renamed. */
    private final Map<String, String> renamedFrom = new LinkedHashMap<>();

    // ---- Structure ---------------------------------------------------------

    public Set<String> categories() {
        return new LinkedHashSet<>(categories.keySet());
    }

    public boolean hasCategory(String name) {
        return categories.containsKey(name);
    }

    /** Live list: the editor changes entries through it. */
    public List<Entry> entries(String category) {
        return categories.computeIfAbsent(category, k -> new ArrayList<>());
    }

    public void addCategory(String name) {
        categories.computeIfAbsent(name, k -> new ArrayList<>());
    }

    public void removeCategory(String name) {
        categories.remove(name);
        renamedFrom.remove(name);
    }

    public void renameCategory(String oldName, String newName) {
        if (!categories.containsKey(oldName) || oldName.equals(newName)) {
            return;
        }
        // Rebuilt in order so the category keeps its rank among the others.
        Map<String, List<Entry>> rebuilt = new LinkedHashMap<>();
        for (var entry : categories.entrySet()) {
            rebuilt.put(entry.getKey().equals(oldName) ? newName : entry.getKey(),
                        entry.getValue());
        }
        categories.clear();
        categories.putAll(rebuilt);
        renamedFrom.put(newName, renamedFrom.getOrDefault(oldName, oldName));
        renamedFrom.remove(oldName);
    }

    /**
     * SYSTEM_PATH and GENERAL are where Sphere's own configuration starts, one
     * line per key. Everywhere else a repeated key stacks, the way a shell profile
     * builds PATH, so a second declaration is added rather than replacing.
     */
    public static boolean isSingleValued(String category) {
        return "SYSTEM_PATH".equalsIgnoreCase(category) || "GENERAL".equalsIgnoreCase(category);
    }

    /** True when the key already exists and the category allows only one line. */
    public boolean wouldReplace(String category, String key) {
        if (!isSingleValued(category)) {
            return false;
        }
        for (Entry entry : categories.getOrDefault(category, List.of())) {
            if (entry.key().equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    public void addEntry(String category, String key, String value) {
        if (isSingleValued(category)) {
            for (Entry entry : entries(category)) {
                if (entry.key().equalsIgnoreCase(key)) {
                    entry.setValue(value);
                    return;
                }
            }
        }
        entries(category).add(new Entry(key, value));
    }

    public void removeEntry(String category, int index) {
        List<Entry> list = categories.get(category);
        if (list != null && index >= 0 && index < list.size()) {
            list.remove(index);
        }
    }

    /** First value declared for a key, or null. */
    public String value(String category, String key) {
        for (Entry entry : categories.getOrDefault(category, List.of())) {
            if (entry.key().equalsIgnoreCase(key)) {
                return entry.value();
            }
        }
        return null;
    }

    // ---- Snapshots ---------------------------------------------------------

    public SettingsFile cloneSnapshot() {
        SettingsFile copy = new SettingsFile();
        copy.copyFrom(this);
        return copy;
    }

    public void copyFrom(SettingsFile other) {
        categories.clear();
        for (var entry : other.categories.entrySet()) {
            List<Entry> list = new ArrayList<>();
            for (Entry e : entry.getValue()) {
                list.add(new Entry(e.key(), e.value(), e.sourceLine()));
            }
            categories.put(entry.getKey(), list);
        }
        rawLines.clear();
        rawLines.addAll(other.rawLines);
        renamedFrom.clear();
        renamedFrom.putAll(other.renamedFrom);
    }

    // ---- Reading -----------------------------------------------------------

    void load(List<String> lines) {
        categories.clear();
        renamedFrom.clear();
        rawLines.clear();
        rawLines.addAll(lines);

        String current = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                current = line.substring(1, line.length() - 1).trim();
                categories.computeIfAbsent(current, k -> new ArrayList<>());
                continue;
            }
            int eq = line.indexOf('=');
            if (current != null && eq > 0) {
                entries(current).add(new Entry(line.substring(0, eq).trim(),
                                               line.substring(eq + 1).trim(), i));
            }
        }
    }

    // ---- Writing -----------------------------------------------------------

    /**
     * Writes through a temporary file and a backup, rewriting the original line by
     * line: only what the user changed moves, and the header, the blank lines and
     * the comments stay where they were.
     */
    public void saveToFile(Path path) throws IOException {
        List<String> out = rawLines.isEmpty() ? render() : merge();

        Path tempFile = path.resolveSibling(path.getFileName() + ".tmp");
        Path backupFile = path.resolveSibling(path.getFileName() + ".bak");

        // Explicit UTF-8: the previous writer used the platform default while the
        // reader assumed UTF-8, which mangles accented characters on Windows.
        Files.write(tempFile, out, StandardCharsets.UTF_8);
        if (Files.exists(path)) {
            Files.copy(path, backupFile, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING,
                                       StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            // Network shares and some Windows volumes refuse an atomic move.
            Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
        }

        // The file just written is the new reference.
        load(new ArrayList<>(out));
    }

    /** Used only when there was no file to start from. */
    private List<String> render() {
        List<String> out = new ArrayList<>();
        for (var category : categories.entrySet()) {
            out.add("[" + category.getKey() + "]");
            for (Entry entry : category.getValue()) {
                out.add(entry.key() + "=" + entry.value());
            }
            out.add("");
        }
        return out;
    }

    private List<String> merge() {
        Map<Integer, Entry> byLine = new LinkedHashMap<>();
        Map<String, String> headerRename = new LinkedHashMap<>();
        for (var category : categories.entrySet()) {
            String fileName = renamedFrom.get(category.getKey());
            if (fileName != null) {
                headerRename.put(fileName, category.getKey());
            }
            for (Entry entry : category.getValue()) {
                if (entry.sourceLine() >= 0) {
                    byLine.put(entry.sourceLine(), entry);
                }
            }
        }
        Set<String> liveInFile = new LinkedHashSet<>();
        for (String name : categories.keySet()) {
            liveInFile.add(renamedFrom.getOrDefault(name, name));
        }

        List<String> out = new ArrayList<>();
        String currentFileCategory = null;
        boolean dropping = false;

        for (int i = 0; i < rawLines.size(); i++) {
            String raw = rawLines.get(i);
            String line = raw.trim();

            if (line.startsWith("[") && line.endsWith("]")) {
                if (currentFileCategory != null && !dropping) {
                    appendNew(out, currentFileCategory);
                }
                String name = line.substring(1, line.length() - 1).trim();
                // Everything under a deleted category goes with it.
                dropping = !liveInFile.contains(name);
                currentFileCategory = name;
                if (!dropping) {
                    String renamed = headerRename.get(name);
                    out.add(renamed == null ? raw : "[" + renamed + "]");
                }
                continue;
            }
            if (dropping) {
                continue;
            }

            int eq = line.indexOf('=');
            if (line.isEmpty() || line.startsWith("#") || eq <= 0) {
                out.add(raw);
                continue;
            }
            Entry entry = byLine.get(i);
            if (entry == null) {
                continue;                       // deleted by the editor
            }
            String prefix = raw.substring(0, raw.indexOf('='));
            // The original spacing survives when the key itself did not change.
            out.add(prefix.trim().equals(entry.key())
                    ? prefix + "=" + entry.value()
                    : entry.key() + "=" + entry.value());
        }
        if (currentFileCategory != null && !dropping) {
            appendNew(out, currentFileCategory);
        }

        // Categories the file never carried.
        for (var category : categories.entrySet()) {
            if (seenInFile(renamedFrom.getOrDefault(category.getKey(), category.getKey()))) {
                continue;
            }
            if (!out.isEmpty() && !out.get(out.size() - 1).isBlank()) {
                out.add("");
            }
            out.add("[" + category.getKey() + "]");
            for (Entry entry : category.getValue()) {
                out.add(entry.key() + "=" + entry.value());
            }
        }
        return out;
    }

    /** Adds the entries a category gained, at the end of its block. */
    private void appendNew(List<String> out, String fileCategoryName) {
        String live = fileCategoryName;
        for (var rename : renamedFrom.entrySet()) {
            if (rename.getValue().equals(fileCategoryName)) {
                live = rename.getKey();
                break;
            }
        }
        List<Entry> list = categories.get(live);
        if (list == null) {
            return;
        }
        List<String> added = new ArrayList<>();
        for (Entry entry : list) {
            if (entry.sourceLine() < 0) {
                added.add(entry.key() + "=" + entry.value());
            }
        }
        if (added.isEmpty()) {
            return;
        }
        int at = out.size();
        while (at > 0 && out.get(at - 1).isBlank()) {
            at--;                               // stay above the blank separator
        }
        out.addAll(at, added);
    }

    private boolean seenInFile(String name) {
        for (String raw : rawLines) {
            String line = raw.trim();
            if (line.startsWith("[") && line.endsWith("]")
                    && line.substring(1, line.length() - 1).trim().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
