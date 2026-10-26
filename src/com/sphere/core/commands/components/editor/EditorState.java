package com.sphere.components.editor;

import javax.swing.Timer;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * What the editor remembers between sessions: recent files, the font size and
 * the view switches. Kept in config/editor_state.properties, apart from
 * settings.conf, which belongs to the toolchains.
 */
public final class EditorState {

    private static final File FILE = new File("config", "editor_state.properties");
    private static final int MAX_RECENT = 20;
    private static final Properties PROPS = load();
    private static Timer saver;

    private EditorState() {
    }

    private static Properties load() {
        Properties p = new Properties();
        if (FILE.isFile()) {
            try (InputStream in = Files.newInputStream(FILE.toPath())) {
                p.load(in);
            } catch (IOException | RuntimeException ignored) {
                // a damaged state file only costs the remembered state
            }
        }
        return p;
    }

    private static synchronized void scheduleSave() {
        if (saver == null) {
            saver = new Timer(700, e -> saveNow());
            saver.setRepeats(false);
        }
        saver.restart();
    }

    /** Writes the state now; through a temporary file so a crash never truncates it. */
    public static synchronized void saveNow() {
        try {
            File dir = FILE.getAbsoluteFile().getParentFile();
            if (dir != null) {
                Files.createDirectories(dir.toPath());
            }
            File tmp = new File(FILE.getPath() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp.toPath())) {
                PROPS.store(out, "SphereCoder state: recent files, split layout and view preferences");
            }
            Files.move(tmp.toPath(), FILE.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException ignored) {
            // the state is a convenience; failing to write it must not disturb editing
        }
    }

    // ---- Values ------------------------------------------------------------

    public static boolean flag(String key, boolean fallback) {
        String v = PROPS.getProperty(key);
        return v == null ? fallback : Boolean.parseBoolean(v.trim());
    }

    public static void setFlag(String key, boolean value) {
        PROPS.setProperty(key, String.valueOf(value));
        scheduleSave();
    }

    public static int number(String key, int fallback) {
        try {
            return Integer.parseInt(PROPS.getProperty(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public static void setNumber(String key, int value) {
        PROPS.setProperty(key, String.valueOf(value));
        scheduleSave();
    }

    public static String text(String key, String fallback) {
        return PROPS.getProperty(key, fallback);
    }

    public static void setText(String key, String value) {
        if (value == null) {
            PROPS.remove(key);
        } else {
            PROPS.setProperty(key, value);
        }
        scheduleSave();
    }

    public static final String MINIMAP = "view.minimap";
    public static final String RESTORE_SESSION = "session.restore";
    public static final String WORD_WRAP = "view.wordWrap";
    public static final String INDENT_GUIDES = "view.indentGuides";
    public static final String OCCURRENCES = "view.occurrences";
    public static final String AUTO_SAVE = "edit.autoSave";
    public static final String AUTO_COMPLETE = "edit.autoComplete";
    public static final String AUTO_CLOSE = "edit.autoClosePairs";
    public static final String TRIM_ON_SAVE = "edit.trimOnSave";
    public static final String LIVE_LINT = "insight.liveLint";
    public static final String FONT_SIZE = "view.fontSize";

    public static int fontSize() {
        return Math.max(8, Math.min(40, number(FONT_SIZE, 13)));
    }

    public static void setFontSize(int size) {
        setNumber(FONT_SIZE, Math.max(8, Math.min(40, size)));
    }

    // ---- Recent files ------------------------------------------------------

    public static List<File> recentFiles() {
        List<File> out = new ArrayList<>();
        for (int i = 0; i < MAX_RECENT; i++) {
            String p = PROPS.getProperty("recent." + i);
            if (p == null) {
                break;
            }
            File f = new File(p);
            if (f.isFile()) {
                out.add(f);
            }
        }
        return out;
    }

    public static void addRecent(File file) {
        if (file == null) {
            return;
        }
        List<File> list = recentFiles();
        File abs = file.getAbsoluteFile();
        list.removeIf(f -> f.getAbsoluteFile().equals(abs));
        list.add(0, abs);
        for (int i = 0; i < MAX_RECENT; i++) {
            PROPS.remove("recent." + i);
        }
        for (int i = 0; i < list.size() && i < MAX_RECENT; i++) {
            PROPS.setProperty("recent." + i, list.get(i).getAbsolutePath());
        }
        scheduleSave();
    }

    public static void clearRecent() {
        for (int i = 0; i < MAX_RECENT; i++) {
            PROPS.remove("recent." + i);
        }
        scheduleSave();
    }

    // ---- Commands used -----------------------------------------------------

    public static List<String> recentCommands() {
        String v = PROPS.getProperty("palette.recent", "");
        List<String> out = new ArrayList<>();
        for (String s : v.split(",")) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    public static void addRecentCommand(String id) {
        List<String> list = recentCommands();
        list.remove(id);
        list.add(0, id);
        while (list.size() > 12) {
            list.remove(list.size() - 1);
        }
        PROPS.setProperty("palette.recent", String.join(",", list));
        scheduleSave();
    }
}
