package com.sphere.core;

import com.sphere.utils.AppLogger;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the commands typed at the prompt in the order they were typed.
 *
 * Each Enter used to hand its line to the common fork-join pool, whose threads
 * take work in no particular order: ":root open big.root" followed quickly by
 * ":root ls" could list before the file was open, and "cd x" then "ls" could
 * list the old folder. A line now waits for the one before it.
 *
 * Waiting has two limits, because commands also have to work as they did:
 * <ul>
 * <li>control commands (":kill", ":watch-stop", ":tail-stop", ...) never wait,
 *     since stopping the command still running is their purpose;</li>
 * <li>a line waits at most {@link #GRACE_MILLIS} for the previous one; a job
 *     that runs for minutes does not lock the prompt, the next line then runs
 *     beside it, as it always did.</li>
 * </ul>
 */
public final class CommandSequencer {

    /** How long a line waits for the one before it before running anyway. */
    static final long GRACE_MILLIS = 15_000;

    private final AtomicInteger counter = new AtomicInteger();
    private final ExecutorService pool = Executors.newCachedThreadPool(task -> {
        Thread t = new Thread(task, "sphere-command-" + counter.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

    /** Queues the line behind the previous one, or runs it at once when it is a control command. */
    public synchronized void submit(String input, Runnable task) {
        if (isControl(input)) {
            pool.execute(task);
            return;
        }
        final CompletableFuture<Void> previous = tail;
        final CompletableFuture<Void> mine = new CompletableFuture<>();
        tail = mine;
        pool.execute(() -> {
            try {
                awaitPrevious(previous, input);
                task.run();
            } finally {
                mine.complete(null);
            }
        });
    }

    private static void awaitPrevious(CompletableFuture<Void> previous, String input) {
        try {
            previous.get(GRACE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException stillRunning) {
            AppLogger.info("The previous command is still running; '" + shorten(input)
                           + "' starts beside it.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException ignored) {
            // the previous command failed on its own; this one still runs
        }
    }

    /** Commands whose job is to stop another: they must not queue behind it. */
    static boolean isControl(String input) {
        if (input == null) {
            return false;
        }
        final String first = input.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        return first.equals(":kill") || first.equals(":stop") || first.equals(":cancel")
            || (first.startsWith(":") && first.endsWith("-stop"));
    }

    private static String shorten(String input) {
        final String s = input == null ? "" : input.strip();
        return s.length() > 40 ? s.substring(0, 37) + "..." : s;
    }
}
