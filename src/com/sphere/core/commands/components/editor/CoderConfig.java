package com.sphere.components.editor;

import com.sphere.components.workspace.MinimalJson;
import com.sphere.utils.AppLogger;

import javax.swing.Timer;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * config/config.spherecoder: what SphereCoder is set up with once and finds
 * again at every launch, as JSON. The explorer keeps there the folders shown
 * beside WorkSpace/, what was unfolded in them, how its two sections share the
 * height, its width and whether it shows. Without the file, deleted or
 * unreadable, the setup starts over: the explorer shows WorkSpace/ alone.
 *
 * The view switches and the recent files stay in editor_state.properties.
 */
public final class CoderConfig {

    private static final File FILE = new File("config", "config.spherecoder");
    private static final int VERSION = 1;

    /** True when the file was there, and readable, at launch. */
    private static boolean found;
    /** True when a change is not on disk yet. */
    private static boolean dirty;
    private static final Map<String, Object> ROOT = load();
    private static Timer saver;

    private CoderConfig() {
    }

    private static Map<String, Object> load() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", VERSION);
        if (FILE.isFile()) {
            try {
                m.putAll(MinimalJson.parseStrict(Files.readString(FILE.toPath(), StandardCharsets.UTF_8)));
                found = true;
            } catch (IOException | RuntimeException damaged) {
                // Set up again, as when the file is gone; the next change writes a sound one.
                AppLogger.warn("config/config.spherecoder could not be read, SphereCoder sets up again: "
                               + damaged.getMessage());
            }
        }
        return m;
    }

    /** False at the first launch, or when the file was deleted: the setup starts over. */
    public static boolean wasFound() {
        return found;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(String name) {
        if (ROOT.get(name) instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Map<String, Object> fresh = new LinkedHashMap<>();
        ROOT.put(name, fresh);
        return fresh;
    }

    // ---- Values ------------------------------------------------------------

    public static String text(String section, String key, String fallback) {
        return section(section).get(key) instanceof String s ? s : fallback;
    }

    public static boolean flag(String section, String key, boolean fallback) {
        return section(section).get(key) instanceof Boolean b ? b : fallback;
    }

    public static int number(String section, String key, int fallback) {
        return section(section).get(key) instanceof Number n ? n.intValue() : fallback;
    }

    public static List<String> texts(String section, String key) {
        List<String> out = new ArrayList<>();
        if (section(section).get(key) instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** Sets a value (null removes it); the file is written a moment later, and only when something changed. */
    public static void put(String section, String key, Object value) {
        Map<String, Object> s = section(section);
        if (same(s.get(key), value)) {
            return;
        }
        if (value == null) {
            s.remove(key);
        } else {
            s.put(key, value);
        }
        dirty = true;
        scheduleSave();
    }

    /** A number read back is a Long, the one written an Integer: the same width is no change. */
    private static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return x.doubleValue() == y.doubleValue();
        }
        return Objects.equals(a, b);
    }

    private static synchronized void scheduleSave() {
        if (saver == null) {
            saver = new Timer(600, e -> flush());
            saver.setRepeats(false);
        }
        saver.restart();
    }

    /**
     * Writes what changed, now; through a temporary file so a crash never
     * leaves half of it. Nothing changed, nothing written: a file deleted
     * while Sphere runs stays deleted, and the next launch sets up again.
     */
    public static synchronized void flush() {
        if (!dirty) {
            return;
        }
        try {
            File dir = FILE.getAbsoluteFile().getParentFile();
            if (dir != null) {
                Files.createDirectories(dir.toPath());
            }
            File tmp = new File(FILE.getPath() + ".tmp");
            Files.writeString(tmp.toPath(), MinimalJson.toJson(ROOT) + System.lineSeparator(), StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), FILE.toPath(), StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException | RuntimeException ex) {
            AppLogger.error("config/config.spherecoder could not be written: " + ex.getMessage());
        }
    }
}
