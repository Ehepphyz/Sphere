package com.sphere.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SessionManager {

    private static final String SESSIONS_DIR = "sessions";
    private static final String FILE_PREFIX = "session_";
    private static final String FILE_SUFFIX = ".log";
    
    private final Path sessionFilePath;
    
    // Fixed pattern and applied standard U.S. date layout: MM-dd-yyyy
    private final DateTimeFormatter logTimeFormatter = DateTimeFormatter.ofPattern("MM-dd-yyyy HH:mm:ss");

    public SessionManager(String sessionName) {
        // Use Java NIO Paths
        Path directory = Paths.get(SESSIONS_DIR);
        
        try {
            if (!Files.exists(directory)) {
                Files.createDirectories(directory);
            }

            // Applied standard U.S. date format to file names safely
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd-yyyy_HH-mm-ss"));
            String fileName = FILE_PREFIX + sessionName + "_" + timestamp + FILE_SUFFIX;
            this.sessionFilePath = directory.resolve(fileName);

            // Print initial header safely
            log(":: Session started at " + timestamp + " ===");

        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize session logger: " + e.getMessage(), e);
        }
    }

    /**
     * Thread-safe logging method.
     */
    public synchronized void log(String message) {
        try {
            String timestamp = LocalDateTime.now().format(logTimeFormatter);
            String fullLine = "[" + timestamp + "] " + message + System.lineSeparator();
            
            // Files.write with APPEND is inherently safer and handles opening/closing automatically
            Files.write(sessionFilePath, fullLine.getBytes(), 
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Failed to write to session log: " + e.getMessage());
        }
    }

    public void write(String message) {
        log(message);
    }

    /**
     * Closes the session elegantly.
     */
    public void close() {
        log(":: Session closed ::");
    }

    // ==========================================
    // SMART UTILITY METHODS (Business Logic)
    // ==========================================

    /**
     * Fetches only valid session files, sorted from newest to oldest.
     */
    public static List<Path> getAllSessions() {
        Path directory = Paths.get(SESSIONS_DIR);
        if (!Files.exists(directory)) return List.of();

        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .filter(path -> Files.isRegularFile(path) 
                            && path.getFileName().toString().startsWith(FILE_PREFIX) 
                            && path.getFileName().toString().endsWith(FILE_SUFFIX))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            System.err.println("Error reading sessions directory: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Retains only the N most recent sessions and purges the rest automatically.
     */
    public static void purgeOldSessions(int retainCount) {
        List<Path> sessions = getAllSessions();
        if (sessions.size() <= retainCount) return;

        // Delete from index 'retainCount' to the end of the list
        for (int i = retainCount; i < sessions.size(); i++) {
            try {
                Files.deleteIfExists(sessions.get(i));
            } catch (IOException e) {
                System.err.println("Failed to delete old session file: " + sessions.get(i) + " - " + e.getMessage());
            }
        }
    }
}
