package com.sphere.core.commandrouterincludes;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages command history, persistent storage, and macro expansion.
 */
public class HistoryManager {

    private final List<String> history = Collections.synchronizedList(new ArrayList<>());
    private int cursor = 0;
    private final File historyFile;

    public HistoryManager() {
        File sessionsDir = new File("sessions");
        sessionsDir.mkdirs();
        this.historyFile = new File(sessionsDir, "history.txt");
        load();
    }

    public synchronized void add(String cmd) {
        if (cmd != null && !cmd.trim().isEmpty()) {
            history.add(cmd);
            cursor = history.size();
        }
    }

    /**
     * Resolves command macros:
     * !!    -> Last command
     * !n    -> Command at index n
     * !?str -> Last command containing str
     */
    public synchronized String expandMacros(String input) {
        if (input == null || !input.startsWith("!")) return input;

        if (input.equals("!!")) {
            return last() != null ? last() : input;
        }

        if (input.startsWith("!?")) {
            String pattern = input.substring(2);
            String found = searchContains(pattern);
            return (found != null) ? found : input;
        }

        if (input.matches("!\\d+")) {
            int index = Integer.parseInt(input.substring(1));
            String found = get(index);
            return (found != null) ? found : input;
        }

        return input;
    }

    public synchronized String last() {
        return history.isEmpty() ? null : history.get(history.size() - 1);
    }

    public synchronized String get(int index) {
        return (index >= 0 && index < history.size()) ? history.get(index) : null;
    }

    public synchronized String searchContains(String pattern) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).contains(pattern)) return history.get(i);
        }
        return null;
    }

    // Navigation methods for GUI
    public synchronized String previous() {
        if (cursor > 0) cursor--;
        return history.isEmpty() ? "" : history.get(cursor);
    }

    public synchronized String next() {
        if (cursor < history.size() - 1) {
            cursor++;
            return history.get(cursor);
        } else {
            cursor = history.size();
            return "";
        }
    }

    public synchronized void save() {
        try {
            Files.write(historyFile.toPath(), history);
        } catch (Exception ignored) {}
    }

    private void load() {
        if (!historyFile.exists()) return;
        try {
            Files.readAllLines(historyFile.toPath()).forEach(this::add);
        } catch (Exception ignored) {}
    }

    /**
     * Searches for the last command containing the pattern.
     */
    public synchronized String search(String pattern) {
        if (pattern == null || pattern.isEmpty()) return "";
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).contains(pattern)) {
                return history.get(i);
            }
        }
        return "";
    }
}
