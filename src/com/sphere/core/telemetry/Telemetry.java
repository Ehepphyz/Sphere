package com.sphere.core.telemetry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the recorded lines are worth once compared with each other.
 *
 * A duration on its own says nothing: four seconds is fast for one program and
 * alarming for another. It becomes a fact when it is held against what the same
 * source usually takes, which is the one thing a running total could never do.
 */
public final class Telemetry {

    /** Below this many lines for a source, its usual time is not established yet. */
    private static final int ENOUGH = 3;

    /** From here on, a build is slow enough to be worth pointing at. */
    public static final double NOTABLE_RATIO = 1.8;

    private Telemetry() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** The usual duration of this kind of work on this source, in milliseconds. */
    public static long usualMillis(String language, RunRecord.Kind kind, String source) {
        List<Long> times = new ArrayList<>();
        for (RunRecord record : RunLog.all()) {
            if (record.kind() == kind && record.language().equals(language)
                && record.source().equals(source) && !record.failed()) {
                times.add(record.millis());
            }
        }
        if (times.size() < ENOUGH) {
            return RunRecord.UNKNOWN;
        }
        times.sort(Comparator.naturalOrder());
        return times.get(times.size() / 2);
    }

    /**
     * How many times its usual duration a line took, or unknown when there is not
     * enough history to say. The median is used rather than the mean: one build
     * that hit a cold cache should not move the reference for all the others.
     */
    public static double ratioToUsual(RunRecord record) {
        final long usual = usualMillis(record.language(), record.kind(), record.source());
        if (usual <= 0 || record.millis() <= 0) {
            return RunRecord.UNKNOWN;
        }
        return record.millis() / (double) usual;
    }

    /** What each set of flags cost, for a source built more than one way. */
    public static Map<String, long[]> byFlags(String language, String source) {
        Map<String, long[]> perFlags = new LinkedHashMap<>();
        for (RunRecord record : RunLog.all()) {
            if (!record.language().equals(language) || !record.source().equals(source)) {
                continue;
            }
            final String key = record.flags().isEmpty() ? "(none)"
                             : String.join(" ", record.flags());
            long[] held = perFlags.computeIfAbsent(key, any -> new long[] {0, 0, 0, 0});
            if (record.kind() == RunRecord.Kind.COMPILE) {
                held[0] += record.millis();
                held[1]++;
            } else {
                held[2] += record.millis();
                held[3]++;
            }
        }
        return perFlags;
    }

    /** The state of one language, or of everything when the language is null. */
    public static String summary(String language) {
        List<RunRecord> kept = new ArrayList<>();
        for (RunRecord record : RunLog.all()) {
            if (language == null || record.language().equals(language)) {
                kept.add(record);
            }
        }
        if (kept.isEmpty()) {
            return "Nothing has been compiled or run yet.";
        }

        long compiles = 0;
        long runs = 0;
        long failures = 0;
        long cached = 0;
        long warnings = 0;
        long compileMillis = 0;
        long runMillis = 0;
        long peak = RunRecord.UNKNOWN;
        RunRecord slowest = null;

        for (RunRecord record : kept) {
            if (record.kind() == RunRecord.Kind.COMPILE) {
                compiles++;
                compileMillis += record.millis();
                warnings += record.warnings();
                if (record.cached()) {
                    cached++;
                }
            } else {
                runs++;
                runMillis += record.millis();
                if (record.peakMemoryKb() > peak) {
                    peak = record.peakMemoryKb();
                }
            }
            if (record.failed()) {
                failures++;
            }
            if (slowest == null || record.millis() > slowest.millis()) {
                slowest = record;
            }
        }

        StringBuilder said = new StringBuilder(language == null ? "All languages" : language);
        said.append(String.format("%n  compiled      %4d time(s)%s", compiles,
            compiles == 0 ? "" : String.format(", median %s", human(compileMillis / compiles))));
        said.append(String.format("%n  ran           %4d time(s)%s", runs,
            runs == 0 ? "" : String.format(", median %s", human(runMillis / runs))));
        said.append(String.format("%n  failed        %4d", failures));
        if (compiles > 0) {
            said.append(String.format("%n  from cache    %4d  (%.0f%%)", cached,
                100.0 * cached / compiles));
            said.append(String.format("%n  warnings      %4d", warnings));
        }
        if (peak > 0) {
            said.append(String.format("%n  peak memory   %s", memory(peak)));
        }
        if (slowest != null) {
            said.append(String.format("%n  slowest       %s on %s, %s",
                slowest.kind().name().toLowerCase(Locale.ROOT),
                slowest.sourceName(), human(slowest.millis())));
        }
        return said.toString();
    }

    /** A duration a person can read at a glance. */
    public static String human(long millis) {
        if (millis < 1000) {
            return millis + " ms";
        }
        if (millis < 60000) {
            return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
        }
        return String.format(Locale.ROOT, "%d min %02d s", millis / 60000, (millis % 60000) / 1000);
    }

    /** A size in kilobytes, written the way a person would say it. */
    public static String memory(long kilobytes) {
        if (kilobytes < 0) {
            return "";
        }
        if (kilobytes < 1024) {
            return kilobytes + " kB";
        }
        if (kilobytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", kilobytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.2f GB", kilobytes / (1024.0 * 1024.0));
    }

    /** A number of bytes, for what a compilation produced. */
    public static String bytes(long size) {
        return size < 0 ? "" : memory(size / 1024);
    }
}
