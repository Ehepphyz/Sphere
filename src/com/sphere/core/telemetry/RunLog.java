package com.sphere.core.telemetry;

import com.sphere.utils.AppLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Everything Sphere has compiled and run, in order.
 *
 * Kept in memory for the session, and written to a file only when asked: a
 * measurement is worth comparing across days, but nobody wants a file growing
 * behind their back.
 */
public final class RunLog {

    /** Past this the oldest lines go, so a long session costs a bounded amount. */
    private static final int MAX_RECORDS = 5000;

    /** The name a save takes when the user gives a folder rather than a file. */
    public static final String FILE_NAME = "telemetry.tsv";

    private static final String HEADER =
        "# started\tlanguage\tkind\tsource\ttool\tflags\tmillis\tbytes"
        + "\texit\twarnings\terrors\tpeak_kb\ttimeout\tcached";

    private static final Deque<RunRecord> RECORDS = new ArrayDeque<>();

    /** Told when a line is added, so the tab follows without being polled. */
    public interface Listener {
        void runsChanged();
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private RunLog() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    public static void add(RunRecord record) {
        if (record == null) {
            return;
        }
        synchronized (RECORDS) {
            RECORDS.addLast(record);
            while (RECORDS.size() > MAX_RECORDS) {
                RECORDS.removeFirst();
            }
        }
        for (Listener listener : LISTENERS) {
            listener.runsChanged();
        }
    }

    public static List<RunRecord> all() {
        synchronized (RECORDS) {
            return new ArrayList<>(RECORDS);
        }
    }

    public static int size() {
        synchronized (RECORDS) {
            return RECORDS.size();
        }
    }

    public static void clear() {
        synchronized (RECORDS) {
            RECORDS.clear();
        }
        for (Listener listener : LISTENERS) {
            listener.runsChanged();
        }
    }

    /** The languages that have produced at least one line, in the order first seen. */
    public static List<String> languages() {
        Set<String> seen = new LinkedHashSet<>();
        for (RunRecord record : all()) {
            seen.add(record.language());
        }
        return new ArrayList<>(seen);
    }

    public static void addListener(Listener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    // ---- keeping it -----------------------------------------------------------

    /** Writes the log where it is asked. A folder gets the default name inside it. */
    public static Path save(Path target) throws IOException {
        Path file = Files.isDirectory(target) ? target.resolve(FILE_NAME) : target;
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        for (RunRecord record : all()) {
            lines.add(asLine(record));
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
        return file;
    }

    /**
     * Reads a log back and puts it before what the session has done.
     *
     * Opening a file adds to the session rather than replacing it: comparing
     * today with last week is the whole reason for keeping one.
     */
    public static int open(Path target) throws IOException {
        Path file = Files.isDirectory(target) ? target.resolve(FILE_NAME) : target;
        List<RunRecord> read = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            RunRecord record = fromLine(line);
            if (record != null) {
                read.add(record);
            }
        }
        if (read.isEmpty()) {
            return 0;
        }
        List<RunRecord> merged = new ArrayList<>(read);
        merged.addAll(all());
        synchronized (RECORDS) {
            RECORDS.clear();
            for (RunRecord record : merged) {
                RECORDS.addLast(record);
                while (RECORDS.size() > MAX_RECORDS) {
                    RECORDS.removeFirst();
                }
            }
        }
        for (Listener listener : LISTENERS) {
            listener.runsChanged();
        }
        return read.size();
    }

    private static String asLine(RunRecord r) {
        return String.join("\t",
            Long.toString(r.startedAt()), r.language(), r.kind().name().toLowerCase(Locale.ROOT),
            escape(r.source()), escape(r.tool()), escape(String.join(" ", r.flags())),
            Long.toString(r.millis()), Long.toString(r.producedBytes()),
            Integer.toString(r.exitCode()), Integer.toString(r.warnings()),
            Integer.toString(r.errors()), Long.toString(r.peakMemoryKb()),
            Boolean.toString(r.timedOut()), Boolean.toString(r.cached()));
    }

    private static RunRecord fromLine(String line) {
        final String[] parts = line.split("\t", -1);
        if (parts.length < 14) {
            return null;
        }
        try {
            final List<String> flags = parts[5].isBlank()
                ? List.of() : List.of(unescape(parts[5]).split(" "));
            return new RunRecord(Long.parseLong(parts[0]), parts[1],
                "compile".equalsIgnoreCase(parts[2]) ? RunRecord.Kind.COMPILE : RunRecord.Kind.RUN,
                unescape(parts[3]), unescape(parts[4]), flags,
                Long.parseLong(parts[6]), Long.parseLong(parts[7]), Integer.parseInt(parts[8]),
                Integer.parseInt(parts[9]), Integer.parseInt(parts[10]), Long.parseLong(parts[11]),
                Boolean.parseBoolean(parts[12]), Boolean.parseBoolean(parts[13]));
        } catch (RuntimeException malformed) {
            AppLogger.error("Skipped a malformed telemetry line.");
            return null;
        }
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    private static String unescape(String text) {
        return text.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\");
    }
}
