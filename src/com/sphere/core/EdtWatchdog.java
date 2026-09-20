package com.sphere.core;

import com.sphere.utils.AppLogger;

import javax.swing.SwingUtilities;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Notices when the interface has stopped answering, and says where it is stuck.
 *
 * One snapshot is enough when the thread is blocked, because it stays on the
 * call that blocked it. It is not enough when the thread is busy: the stack
 * moves the whole time, and a single picture of it names whatever happened to be
 * running at that instant. So the stall is sampled several times: the calls
 * common to every sample are the ones that are stuck, and the calls that change
 * above them are where the time is going.
 */
public final class EdtWatchdog {

    /** How often the event thread is asked to answer. */
    private static final long CHECK_MILLIS = 1000;

    /** How long the delay may last before it counts as a freeze. */
    private static final long DEFAULT_PATIENCE_MILLIS = 5000;

    /** Below this the report would fire on ordinary work rather than on a freeze. */
    private static final long MINIMUM_PATIENCE_MILLIS = 1000;

    /** Read once at start: a slow machine may legitimately need longer. */
    private static long patienceMillis = DEFAULT_PATIENCE_MILLIS;

    /** How often the stuck thread is looked at once a freeze is under way. */
    private static final long SAMPLE_MILLIS = 200;

    /** How many looks are taken before the report is written. */
    private static final int SAMPLES = 12;

    /** How many of the common frames are worth printing. */
    private static final int FRAMES = 60;

    /** How many of the changing frames are worth printing. */
    private static final int BUSY_FRAMES = 12;

    private static Thread worker;

    /**
     * Set while the event thread is expected to block. Showing a window for the
     * first time builds the render pipeline and lays out every component, which
     * takes as long as the machine takes; there is no call to move elsewhere, so
     * reporting it would only be noise at every launch.
     */
    private static final AtomicBoolean EXPECTED = new AtomicBoolean(false);

    /** False until the launch is over, which is decided by CALM below. */
    private static final AtomicBoolean SHOWN = new AtomicBoolean(false);

    /**
     * How many checks in a row the event thread must answer promptly before the
     * launch counts as finished.
     *
     * Showing the window is not the end of a launch: the backends start, the
     * icons are parsed, the clipboard and the colours of the desktop are read,
     * and each of those still blocks the event thread. Waiting for the interface
     * to answer three times in a row is what tells that it has settled, and no
     * call site has to say so.
     */
    private static final int CALM_CHECKS = 3;

    private static final java.util.concurrent.atomic.AtomicInteger CALM =
        new java.util.concurrent.atomic.AtomicInteger(0);

    /** Two reports are never closer than this, however bad the machine is. */
    private static final long REPORT_GAP_MILLIS = 15000;

    private static final AtomicLong LAST_REPORT = new AtomicLong(0);

    /**
     * What the launch is allowed to take before it counts as stuck.
     *
     * A launch loads classes, parses the icons, opens the backends and waits on
     * the X server; on a machine drawing through llvmpipe that is seconds, not
     * milliseconds. Reporting it would fire at every start, so only a launch
     * that is truly stuck reaches this figure.
     */
    private static final long STARTUP_PATIENCE_MILLIS = 60000;

    /** When the event thread last answered. Restarted when a busy stretch ends. */
    private static final AtomicLong LAST_ANSWER = new AtomicLong(System.currentTimeMillis());

    /** One report per stall, not one per second. */
    private static final AtomicBoolean REPORTED = new AtomicBoolean(false);

    private EdtWatchdog() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    public static synchronized void start() {
        if (worker != null) {
            return;
        }
        patienceMillis = readPatience();
        LAST_ANSWER.set(System.currentTimeMillis());

        worker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(CHECK_MILLIS);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return;
                }
                SwingUtilities.invokeLater(() -> {
                    LAST_ANSWER.set(System.currentTimeMillis());
                    REPORTED.set(false);
                });

                final long now = System.currentTimeMillis();
                final long silent = now - LAST_ANSWER.get();

                // Answered on time: that is what a settled interface looks like.
                if (silent < CHECK_MILLIS * 2) {
                    if (!SHOWN.get() && CALM.incrementAndGet() >= CALM_CHECKS) {
                        SHOWN.set(true);
                    }
                } else {
                    CALM.set(0);
                }

                final long allowed = SHOWN.get()
                    ? patienceMillis
                    : Math.max(patienceMillis, STARTUP_PATIENCE_MILLIS);
                final boolean quietEnough =
                    now - LAST_REPORT.get() >= REPORT_GAP_MILLIS;
                if (silent >= allowed && !EXPECTED.get() && quietEnough
                    && REPORTED.compareAndSet(false, true)) {
                    report(silent, LAST_ANSWER);
                }
            }
        }, "sphere-edt-watchdog");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    /**
     * EDT_PATIENCE_MS in settings.conf, in milliseconds, or the default.
     *
     * What counts as a freeze depends on the machine: a workstation that takes
     * four seconds to draw a large canvas is not broken, and being told so at
     * every launch teaches the reader to ignore the message.
     */
    private static long readPatience() {
        try {
            final String written =
                new com.sphere.utils.SettingsManager().getProperty("EDT_PATIENCE_MS");
            if (written == null || written.isBlank()) {
                return DEFAULT_PATIENCE_MILLIS;
            }
            final long asked = Long.parseLong(written.trim());
            if (asked < MINIMUM_PATIENCE_MILLIS) {
                AppLogger.warn("EDT_PATIENCE_MS is below " + MINIMUM_PATIENCE_MILLIS
                               + " ms, which would report ordinary work; using "
                               + MINIMUM_PATIENCE_MILLIS + ".");
                return MINIMUM_PATIENCE_MILLIS;
            }
            return asked;
        } catch (NumberFormatException notANumber) {
            AppLogger.error("EDT_PATIENCE_MS is not a number of milliseconds.");
            return DEFAULT_PATIENCE_MILLIS;
        } catch (RuntimeException unreadable) {
            return DEFAULT_PATIENCE_MILLIS;
        }
    }

    /**
     * Marks a stretch where the event thread is meant to be busy.
     *
     * Leaving one restarts the clock: the silence the stretch caused is not a
     * freeze, and without this the next check reported it the moment the
     * stretch ended. The first stretch to end is the launch, after which the
     * ordinary patience applies.
     */
    public static void expectBusy(boolean busy) {
        EXPECTED.set(busy);
        if (!busy) {
            LAST_ANSWER.set(System.currentTimeMillis());
            REPORTED.set(false);
        }
    }

    public static synchronized void stop() {
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    private static void report(long silentMillis, AtomicLong lastAnswer) {
        List<StackTraceElement[]> samples = new ArrayList<>();
        String state = "unknown";
        String lock = null;

        final long answeredBefore = lastAnswer.get();
        for (int taken = 0; taken < SAMPLES; taken++) {
            ThreadInfo info = eventThread();
            if (info == null) {
                break;
            }
            samples.add(info.getStackTrace());
            state = String.valueOf(info.getThreadState());
            if (info.getLockName() != null) {
                lock = info.getLockName()
                       + (info.getLockOwnerName() == null
                          ? "" : " held by " + info.getLockOwnerName());
            }
            try {
                Thread.sleep(SAMPLE_MILLIS);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                break;
            }
            if (lastAnswer.get() != answeredBefore) {
                // It answered while being watched: a slow moment, not a freeze.
                // Reporting it would print an idle stack and use up the quiet
                // period that a real freeze needs.
                return;
            }
        }
        if (samples.isEmpty()) {
            return;
        }
        LAST_REPORT.set(System.currentTimeMillis());

        StringBuilder message = new StringBuilder();
        message.append("The interface has not answered for ")
               .append(silentMillis / 1000).append(" s.")
               .append("\n  state ").append(state);
        if (lock != null) {
            message.append(", waiting on ").append(lock);
        }
        message.append("  (").append(samples.size()).append(" samples)");

        final int shared = commonDepth(samples);
        final StackTraceElement[] last = samples.get(samples.size() - 1);

        message.append("\n  stuck in:");
        for (int i = last.length - shared; i < last.length && i < last.length - shared + FRAMES; i++) {
            message.append("\n    ").append(last[i]);
        }
        if (shared > FRAMES) {
            message.append("\n    ... and ").append(shared - FRAMES).append(" more");
        }

        Map<String, Integer> busy = busyFrames(samples, shared);
        if (!busy.isEmpty()) {
            message.append("\n  time spent in:");
            int shown = 0;
            for (Map.Entry<String, Integer> frame : busy.entrySet()) {
                message.append("\n    ").append(frame.getValue()).append("x  ")
                       .append(frame.getKey());
                if (++shown >= BUSY_FRAMES) {
                    break;
                }
            }
        }
        AppLogger.error(message.toString());
    }

    private static ThreadInfo eventThread() {
        for (ThreadInfo info : ManagementFactory.getThreadMXBean()
                                                .dumpAllThreads(false, false)) {
            if (info != null && info.getThreadName().startsWith("AWT-EventQueue")) {
                return info;
            }
        }
        return null;
    }

    /** How many frames, counted from the outermost call, every sample shares. */
    private static int commonDepth(List<StackTraceElement[]> samples) {
        int depth = 0;
        final StackTraceElement[] first = samples.get(0);
        while (depth < first.length) {
            final StackTraceElement frame = first[first.length - 1 - depth];
            for (StackTraceElement[] sample : samples) {
                if (depth >= sample.length
                    || !frame.equals(sample[sample.length - 1 - depth])) {
                    return depth;
                }
            }
            depth++;
        }
        return depth;
    }

    /** The innermost call of each sample, above what they all share, most seen first. */
    private static Map<String, Integer> busyFrames(List<StackTraceElement[]> samples,
                                                   int shared) {
        Map<String, Integer> counted = new LinkedHashMap<>();
        for (StackTraceElement[] sample : samples) {
            if (sample.length <= shared) {
                continue;
            }
            final String frame = sample[0].toString();
            counted.merge(frame, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> ordered = new ArrayList<>(counted.entrySet());
        ordered.sort((a, b) -> b.getValue() - a.getValue());

        Map<String, Integer> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : ordered) {
            sorted.put(entry.getKey(), entry.getValue());
        }
        return sorted;
    }
}
