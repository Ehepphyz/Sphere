package com.sphere.core.exec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The lines typed in a permanent mode that have not run yet.
 *
 * A line that stands on its own leaves at once, so what is held here is the
 * block still being written, and, in Fortran, the program being built up. Each
 * mode keeps its own lines.
 */
public final class CodeBuffer {

    /** Past this, a paste is more likely an accident than a program. */
    private static final int MAX_LINES = 20000;

    /** The modes whose lines are collected instead of being run one by one. */
    private static final List<String> COLLECTING =
        List.of("py", "cpp", "julia", "fortran");

    private static final Map<String, List<String>> LINES = new LinkedHashMap<>();

    private CodeBuffer() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** Whether a typed line in this mode is code rather than a shell command. */
    public static boolean collects(String mode) {
        return mode != null && COLLECTING.contains(mode);
    }

    public static synchronized void add(String mode, String line) {
        if (mode == null || line == null) {
            return;
        }
        List<String> kept = LINES.computeIfAbsent(mode, any -> new ArrayList<>());
        if (kept.size() >= MAX_LINES) {
            return;
        }
        kept.add(line);
    }

    public static synchronized List<String> lines(String mode) {
        List<String> kept = LINES.get(mode);
        return kept == null ? List.of() : List.copyOf(kept);
    }

    /** The block as one piece of source, ready for the interpreter. */
    public static synchronized String text(String mode) {
        return String.join("\n", lines(mode));
    }

    public static synchronized int size(String mode) {
        List<String> kept = LINES.get(mode);
        return kept == null ? 0 : kept.size();
    }

    public static synchronized boolean isEmpty(String mode) {
        return size(mode) == 0;
    }

    public static synchronized void clear(String mode) {
        if (mode == null) {
            LINES.clear();
        } else {
            LINES.remove(mode);
        }
    }

    // ---- taking lines back --------------------------------------------------
    // Fortran is compiled as it is written, so a statement the compiler refuses
    // has to leave the program again.

    /** Removes lines from through to, both counted from 1. Returns how many went. */
    public static synchronized int delete(String mode, int from, int to) {
        List<String> kept = LINES.get(mode);
        if (kept == null || from < 1 || to < from || from > kept.size()) {
            return 0;
        }
        final int last = Math.min(to, kept.size());
        kept.subList(from - 1, last).clear();
        return last - from + 1;
    }
}
