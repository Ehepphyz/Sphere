package com.sphere.core.clhep.random;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The files the engines save their states in and restore them from (saveStatus, the distributions'
 * saveEngineStatus...): the working directory's, as CLHEP's, or, while one of CLHEP's tests runs ported
 * ({@link #inMemory}), the files of a map, by name, which the test's outputs are compared with.
 */
public final class StatusFiles {

    private static final ThreadLocal<Map<String, StringBuilder>> MEMORY = new ThreadLocal<>();

    private StatusFiles() {
    }

    /** Runs body with this thread's status files those of the map given. */
    public static <T> T inMemory(Map<String, StringBuilder> files, Supplier<T> body) {
        final Map<String, StringBuilder> before = MEMORY.get();
        MEMORY.set(files);
        try {
            return body.get();
        } finally {
            if (before == null) MEMORY.remove();
            else MEMORY.set(before);
        }
    }

    /** A file's text; null when it cannot be read. */
    static String read(String filename) {
        final Map<String, StringBuilder> m = MEMORY.get();
        if (m != null) {
            final StringBuilder b = m.get(filename);
            return b == null ? null : b.toString();
        }
        try {
            return Files.readString(Path.of(filename), StandardCharsets.ISO_8859_1);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** A file written anew (std::ofstream); false when it cannot be. */
    static boolean write(String filename, String text) {
        final Map<String, StringBuilder> m = MEMORY.get();
        if (m != null) {
            m.computeIfAbsent(filename, n -> new StringBuilder()).setLength(0);
            m.get(filename).append(text);
            return true;
        }
        try {
            Files.writeString(Path.of(filename), text, StandardCharsets.ISO_8859_1);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Text added at a file's end, the file made when there is none (std::ofstream with std::ios::app). */
    static boolean append(String filename, String text) {
        final Map<String, StringBuilder> m = MEMORY.get();
        if (m != null) {
            m.computeIfAbsent(filename, n -> new StringBuilder()).append(text);
            return true;
        }
        try {
            Files.writeString(Path.of(filename), text, StandardCharsets.ISO_8859_1, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
