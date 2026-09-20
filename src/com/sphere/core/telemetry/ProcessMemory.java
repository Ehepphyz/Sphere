package com.sphere.core.telemetry;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The highest resident memory a process reached while it ran.
 *
 * An analysis that loads ROOT trees is judged as much by what it holds as by
 * what it takes: a run that climbs to six gigabytes before finishing is worth
 * knowing about. No runtime reports this for a child process, so it is sampled
 * while the process is alive. The mechanism is one; only the reading differs
 * from one system to the next.
 */
public final class ProcessMemory {

    /** How often the process is asked how much it holds. */
    private static final long LINUX_INTERVAL_MS = 100;

    /** Elsewhere a command has to be run for each reading, so it is asked less often. */
    private static final long COMMAND_INTERVAL_MS = 250;

    private static final boolean LINUX =
        System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux");
    private static final boolean WINDOWS =
        System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    private ProcessMemory() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** Follows a process until it ends, then hands back its peak in kilobytes. */
    public interface Watcher extends AutoCloseable {
        long peakKilobytes();

        @Override
        void close();
    }

    /** Starts following a process. Closing the watcher stops the sampling. */
    public static Watcher watch(Process process) {
        if (process == null) {
            return new Watcher() {
                @Override public long peakKilobytes() { return RunRecord.UNKNOWN; }
                @Override public void close() { }
            };
        }
        final long pid = process.pid();
        final AtomicLong peak = new AtomicLong(RunRecord.UNKNOWN);
        final long interval = LINUX ? LINUX_INTERVAL_MS : COMMAND_INTERVAL_MS;

        Thread sampler = new Thread(() -> {
            while (process.isAlive()) {
                final long held = residentKilobytes(pid);
                if (held > peak.get()) {
                    peak.set(held);
                }
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            // Linux keeps the high-water mark itself, which beats any sampling, but
            // only while the process is there to be read.
            final long mark = peak.get();
            if (mark > 0) {
                peak.set(mark);
            }
        }, "sphere-memory-" + pid);
        sampler.setDaemon(true);
        sampler.setPriority(Thread.MIN_PRIORITY);
        sampler.start();

        return new Watcher() {
            @Override
            public long peakKilobytes() {
                return peak.get();
            }

            @Override
            public void close() {
                sampler.interrupt();
            }
        };
    }

    // ---- reading one process -------------------------------------------------

    /** What the process holds now, in kilobytes, or unknown when it cannot be read. */
    public static long residentKilobytes(long pid) {
        try {
            if (LINUX) {
                return fromProc(pid);
            }
            return WINDOWS ? fromTasklist(pid) : fromPs(pid);
        } catch (RuntimeException unreadable) {
            return RunRecord.UNKNOWN;
        }
    }

    /** VmHWM is the peak the kernel itself recorded; VmRSS is what is held now. */
    private static long fromProc(long pid) {
        try {
            Path status = Path.of("/proc", Long.toString(pid), "status");
            long resident = RunRecord.UNKNOWN;
            for (String line : Files.readAllLines(status, StandardCharsets.UTF_8)) {
                if (line.startsWith("VmHWM:")) {
                    return kilobytesIn(line);
                }
                if (line.startsWith("VmRSS:")) {
                    resident = kilobytesIn(line);
                }
            }
            return resident;
        } catch (Exception gone) {
            return RunRecord.UNKNOWN;
        }
    }

    /** macOS and the other unix systems: ps reports the resident size in kilobytes. */
    private static long fromPs(long pid) {
        final String answer = ask(List.of("ps", "-o", "rss=", "-p", Long.toString(pid)));
        if (answer == null) {
            return RunRecord.UNKNOWN;
        }
        try {
            return Long.parseLong(answer.trim());
        } catch (NumberFormatException notANumber) {
            return RunRecord.UNKNOWN;
        }
    }

    /** Windows: tasklist writes the memory as the last field, such as "12 345 K". */
    private static long fromTasklist(long pid) {
        final String answer = ask(List.of("tasklist", "/NH", "/FI",
                                          "PID eq " + pid, "/FO", "CSV"));
        if (answer == null || !answer.contains(",")) {
            return RunRecord.UNKNOWN;
        }
        final int lastComma = answer.lastIndexOf(',');
        final String field = answer.substring(lastComma + 1)
                                   .replace("\"", "").replace("K", "")
                                   .replaceAll("[^0-9]", "");
        if (field.isEmpty()) {
            return RunRecord.UNKNOWN;
        }
        try {
            return Long.parseLong(field);
        } catch (NumberFormatException notANumber) {
            return RunRecord.UNKNOWN;
        }
    }

    private static long kilobytesIn(String line) {
        final String digits = line.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? RunRecord.UNKNOWN : Long.parseLong(digits);
    }

    /** Runs a short command and hands back its first non-empty line. */
    private static String ask(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (!line.isBlank()) {
                        return line;
                    }
                }
            }
            return null;
        } catch (Exception unavailable) {
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
