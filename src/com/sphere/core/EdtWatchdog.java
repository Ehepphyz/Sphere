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

    /** How long it may take before the delay counts as a freeze. */
    private static final long PATIENCE_MILLIS = 5000;

    /** How often the stuck thread is looked at once a freeze is under way. */
    private static final long SAMPLE_MILLIS = 200;

    /** How many looks are taken before the report is written. */
    private static final int SAMPLES = 12;

    /** How many of the common frames are worth printing. */
    private static final int FRAMES = 60;

    /** How many of the changing frames are worth printing. */
    private static final int BUSY_FRAMES = 12;

    private static Thread worker;

    private EdtWatchdog() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    public static synchronized void start() {
        if (worker != null) {
            return;
        }
        final AtomicLong lastAnswer = new AtomicLong(System.currentTimeMillis());
        final AtomicBoolean reported = new AtomicBoolean(false);

        worker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(CHECK_MILLIS);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return;
                }
                SwingUtilities.invokeLater(() -> {
                    lastAnswer.set(System.currentTimeMillis());
                    reported.set(false);
                });

                final long silent = System.currentTimeMillis() - lastAnswer.get();
                if (silent >= PATIENCE_MILLIS && reported.compareAndSet(false, true)) {
                    report(silent, lastAnswer);
                }
            }
        }, "sphere-edt-watchdog");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
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
                break;      // it started answering again
            }
        }
        if (samples.isEmpty()) {
            return;
        }

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
