package com.sphere.core.rootlab.bench;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs suites of the ROOT Lab: the tests of a suite one after the other, the
 * suites side by side on as many threads as the machine has cores (they read
 * different files and share nothing), and tells a listener as each test
 * starts and ends, so a panel can follow the run live.
 */
public final class LabRunner {

    /** Told of the run's progress; called from the runner's threads. */
    public interface Listener {
        default void suiteStarted(LabSuite suite) {
        }

        default void testStarted(LabSuite suite, LabTest test) {
        }

        default void testFinished(LabSuite suite, LabResult result) {
        }

        default void suiteFinished(LabSuite suite, List<LabResult> results) {
        }

        default void log(LabSuite suite, String line) {
        }

        default void finished(Summary summary) {
        }
    }

    /** The totals of a run. */
    public record Summary(int passed, int failed, int errors, int skipped, long nanos, List<LabResult> results) {
        public boolean ok() {
            return failed == 0 && errors == 0;
        }

        public int total() {
            return passed + failed + errors + skipped;
        }
    }

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile ExecutorService pool;

    public void cancel() {
        cancelled.set(true);
        final ExecutorService p = pool;
        if (p != null) {
            p.shutdownNow();
        }
    }

    /** Runs the suites and waits for them. Network suites are skipped unless allowed. */
    public Summary run(List<LabSuite> suites, boolean network, Listener listener) {
        cancelled.set(false);
        final Listener l = listener == null ? new Listener() { } : listener;
        final long t0 = System.nanoTime();
        final List<LabResult> all = Collections.synchronizedList(new ArrayList<>());
        final int threads = Math.max(1, Math.min(suites.size(), Runtime.getRuntime().availableProcessors()));
        final AtomicInteger counter = new AtomicInteger();
        pool = Executors.newFixedThreadPool(threads, task -> {
            Thread t = new Thread(task, "sphere-rootlab-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        final List<Future<?>> running = new ArrayList<>();
        for (LabSuite suite : suites) {
            running.add(pool.submit(() -> all.addAll(runSuite(suite, network, l))));
        }
        for (Future<?> f : running) {
            try {
                f.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException ignored) {
                // a suite that died is reported by runSuite itself
            }
        }
        pool.shutdown();
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        pool = null;
        int passed = 0;
        int failed = 0;
        int errors = 0;
        int skipped = 0;
        final List<LabResult> ordered = new ArrayList<>();
        for (LabSuite suite : suites) {
            for (LabResult r : new ArrayList<>(all)) {
                if (r.suiteId().equals(suite.id())) {
                    ordered.add(r);
                }
            }
        }
        for (LabResult r : ordered) {
            switch (r.status()) {
                case PASSED -> passed++;
                case FAILED -> failed++;
                case ERROR -> errors++;
                case SKIPPED -> skipped++;
            }
        }
        final Summary summary = new Summary(passed, failed, errors, skipped, System.nanoTime() - t0, ordered);
        l.finished(summary);
        return summary;
    }

    private List<LabResult> runSuite(LabSuite suite, boolean network, Listener l) {
        final List<LabResult> results = new ArrayList<>();
        l.suiteStarted(suite);
        final LabContext ctx = new LabContext(line -> l.log(suite, line), cancelled::get, network);
        List<LabTest> tests;
        try {
            tests = suite.tests();
        } catch (RuntimeException broken) {
            final LabResult r = new LabResult(suite.id(), suite.id(), suite.title(), LabStatus.ERROR,
                "the suite could not be built: " + broken, List.of(), 0);
            results.add(r);
            l.testFinished(suite, r);
            l.suiteFinished(suite, results);
            return results;
        }
        for (LabTest test : tests) {
            LabResult r;
            if (cancelled.get()) {
                r = new LabResult(suite.id(), test.id(), test.title(), LabStatus.SKIPPED, "run cancelled", List.of(), 0);
            } else if (suite.network() && !network) {
                r = new LabResult(suite.id(), test.id(), test.title(), LabStatus.SKIPPED,
                    "reads from root.cern: allow network tests to run it", List.of(), 0);
            } else {
                l.testStarted(suite, test);
                final long t0 = System.nanoTime();
                try {
                    test.body().run(ctx);
                    r = new LabResult(suite.id(), test.id(), test.title(), LabStatus.PASSED, "",
                        ctx.takeDetails(), System.nanoTime() - t0);
                } catch (LabFailure failure) {
                    final List<String> d = new ArrayList<>(failure.details);
                    for (String s : ctx.takeDetails()) {
                        if (!d.contains(s)) {
                            d.add(s);
                        }
                    }
                    r = new LabResult(suite.id(), test.id(), test.title(), LabStatus.FAILED,
                        failure.getMessage(), d, System.nanoTime() - t0);
                } catch (LabSkip skip) {
                    r = new LabResult(suite.id(), test.id(), test.title(), LabStatus.SKIPPED,
                        skip.getMessage(), ctx.takeDetails(), System.nanoTime() - t0);
                } catch (Exception | Error crash) {
                    final List<String> d = ctx.takeDetails();
                    d.add(stackOf(crash));
                    r = new LabResult(suite.id(), test.id(), test.title(), LabStatus.ERROR,
                        crash.getClass().getSimpleName() + ": " + crash.getMessage(), d, System.nanoTime() - t0);
                }
            }
            results.add(r);
            l.testFinished(suite, r);
        }
        ctx.cleanup();
        l.suiteFinished(suite, results);
        return results;
    }

    private static String stackOf(Throwable t) {
        StringWriter w = new StringWriter();
        t.printStackTrace(new PrintWriter(w));
        final String s = w.toString();
        return s.length() > 4000 ? s.substring(0, 4000) + "..." : s;
    }

    /** A test line the way ROOT's stress programs print it: "Test  n : title...... OK". */
    public static String line(int number, String title, LabStatus status) {
        final int width = 65;
        String header = number > 0 ? String.format(Locale.ROOT, "Test %2d : %s", number, title)
                                   : "        : " + title;
        StringBuilder b = new StringBuilder(header.length() > width - 2 ? header.substring(0, width - 2) : header);
        while (b.length() < width - 1) {
            b.append('.');
        }
        b.append(' ').append(status.word);
        return b.toString();
    }

    /** The whole run as text, ROOT style, with what failed and why. */
    public static String report(List<LabSuite> suites, Summary summary) {
        StringBuilder b = new StringBuilder();
        final String stars = "*".repeat(72);
        b.append(stars).append('\n');
        b.append("*  Sphere ROOT Lab - ROOT's test/ programs, ported to Java\n");
        b.append("*  ").append(com.sphere.Sphere.VERSION).append(", following ROOT ")
         .append(com.sphere.Sphere.ROOT_SOURCES_VERSION).append(", Java ").append(Runtime.version()).append('\n');
        b.append(stars).append('\n');
        int n = 0;
        for (LabSuite suite : suites) {
            b.append('\n').append(suite.id()).append(" -- ").append(suite.title()).append('\n');
            b.append("  (").append(suite.origin()).append(")\n");
            for (LabResult r : summary.results()) {
                if (!r.suiteId().equals(suite.id())) {
                    continue;
                }
                b.append(line(++n, r.title(), r.status()));
                b.append(String.format(Locale.ROOT, "  %7.3f s", r.seconds())).append('\n');
                if (r.status() != LabStatus.PASSED && !r.message().isEmpty()) {
                    b.append("          ").append(r.message()).append('\n');
                }
                if (r.status() == LabStatus.FAILED || r.status() == LabStatus.ERROR) {
                    int shown = 0;
                    for (String d : r.details()) {
                        if (++shown > 8) {
                            b.append("          ...\n");
                            break;
                        }
                        b.append("          ").append(d.replace("\n", "\n          ")).append('\n');
                    }
                }
            }
        }
        b.append('\n').append(stars).append('\n');
        b.append(String.format(Locale.ROOT, "*  %d passed, %d failed, %d errors, %d skipped, in %.2f s%n",
            summary.passed(), summary.failed(), summary.errors(), summary.skipped(), summary.nanos() / 1e9));
        b.append(stars).append('\n');
        return b.toString();
    }
}
