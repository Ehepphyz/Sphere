package com.sphere.core.telemetry;

import java.util.List;

/**
 * One compilation, or one run, as it happened.
 *
 * Totals were what Sphere kept before, and a total hides the case worth looking
 * at: one build of eight seconds disappears into the average of two hundred
 * builds of one. Keeping each line is what lets a build be compared with the
 * ones that came before it.
 */
public record RunRecord(
        long startedAt,
        String language,
        Kind kind,
        String source,
        String tool,
        List<String> flags,
        long millis,
        long producedBytes,
        int exitCode,
        int warnings,
        int errors,
        long peakMemoryKb,
        boolean timedOut,
        boolean cached) {

    /** What the line is about. */
    public enum Kind { COMPILE, RUN }

    /** Unknown rather than zero, for what a language cannot report. */
    public static final long UNKNOWN = -1;

    public RunRecord {
        flags = flags == null ? List.of() : List.copyOf(flags);
        language = fullName(language);
        source = source == null ? "" : source;
        tool = tool == null ? "" : tool;
    }

    /**
     * One name per language, whichever abbreviation the command used.
     *
     * The console accepts py, jul and fort; a history that mixed those with their
     * full names would never group two runs of the same language together.
     */
    private static String fullName(String language) {
        if (language == null || language.isBlank()) {
            return "";
        }
        return switch (language.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "py", "python3", "python" -> "python";
            case "jul", "julia" -> "julia";
            case "fort", "fot", "f90", "fortran" -> "fortran";
            case "cpp", "c++", "cxx" -> "cpp";
            default -> language.trim().toLowerCase(java.util.Locale.ROOT);
        };
    }

    /** A compilation that produced a binary. */
    public static RunRecord compile(String language, String source, String tool,
                                    List<String> flags, long millis, long producedBytes,
                                    int exitCode, int warnings, int errors, boolean cached) {
        return new RunRecord(System.currentTimeMillis(), language, Kind.COMPILE, source, tool,
                             flags, millis, producedBytes, exitCode, warnings, errors,
                             UNKNOWN, false, cached);
    }

    /** A run of something already built, or of a script. */
    public static RunRecord run(String language, String source, String tool, List<String> flags,
                                long millis, int exitCode, long peakMemoryKb, boolean timedOut) {
        return new RunRecord(System.currentTimeMillis(), language, Kind.RUN, source, tool,
                             flags, millis, UNKNOWN, exitCode, 0, 0,
                             peakMemoryKb, timedOut, false);
    }

    public boolean failed() {
        return timedOut || errors > 0 || (exitCode != 0 && exitCode != (int) UNKNOWN);
    }

    /** The file name alone, which is what a table has room for. */
    public String sourceName() {
        final int slash = Math.max(source.lastIndexOf('/'), source.lastIndexOf('\\'));
        return slash < 0 ? source : source.substring(slash + 1);
    }
}
