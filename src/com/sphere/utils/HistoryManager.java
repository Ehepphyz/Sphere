package com.sphere.utils;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class HistoryManager {

    private final List<String> history = new ArrayList<>();
    private int cursor = 0;

    // History file stored inside /sessions
    private final File historyFile;

    public HistoryManager() {

        // Ensure /sessions exists
        File sessionsDir = new File("sessions");
        sessionsDir.mkdirs();

        // Store history.txt inside /sessions
        this.historyFile = new File(sessionsDir, "history.txt");

        load();
    }

    // Add a command to history
    public void add(String command) {
        if (!command.trim().isEmpty()) {
            history.add(command);
            cursor = history.size();
        }
    }

    // Navigate backward in history (UP arrow)
    public String previous() {
        if (cursor > 0) {
            cursor--;
            return history.get(cursor);
        }
        return history.isEmpty() ? "" : history.get(cursor);
    }

    // Navigate forward in history (DOWN arrow)
    public String next() {
        if (cursor < history.size() - 1) {
            cursor++;
            return history.get(cursor);
        } else {
            cursor = history.size();
            return "";
        }
    }

    // Ctrl+R search through history
    public String search(String pattern) {
        if (pattern == null || pattern.isEmpty()) return "";

        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).contains(pattern)) {
                cursor = i; // Correct field
                return history.get(i);
            }
        }
        return "";
    }

    // Save history to /sessions/history.txt
    public void save() {
        try {
            Files.write(historyFile.toPath(), history);
        } catch (Exception ignored) {}
    }

    // Load history at startup
    private void load() {
        if (!historyFile.exists()) return;

        try {
            Files.readAllLines(historyFile.toPath()).forEach(history::add);
            cursor = history.size();
        } catch (Exception ignored) {}
    }
}

