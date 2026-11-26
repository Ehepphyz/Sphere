package com.sphere.core.golem95;

import com.sphere.Sphere;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Golem95 in Sphere: the versions, and the engines that compute side by side.
 *
 * <p>Golem95 (T. Binoth, J.-Ph. Guillet, G. Heinrich, E. Pilon, T. Reiter; for Golem95C and the higher
 * ranks also G. Cullen, T. Kleinschmidt, M. Rodgers, J.F. von Soden-Fraunhofen) computes the one-loop
 * N-point tensor integrals, N up to 6, as form factors in a basis that avoids inverse Gram determinants
 * where they vanish; it carries OneLOop (A. van Hameren) for the scalar integrals. Sphere's Golem95 is a Java translation of both, made by Sphere's Fortran translator and
 * checked against the Fortran compiled by gfortran; on top of it {@link GolemEngine} gives the library a
 * Java interface and {@link Reliability} says how far each answer can be trusted.
 */
public final class Golem95 {

    public static final String VERSION = Sphere.GOLEM95_VERSION;
    public static final String ONELOOP_VERSION = Sphere.ONELOOP_VERSION;
    public static final String JAVA_PORT_VERSION = Sphere.GOLEM95_JAVA_PORT_VERSION;

    private Golem95() {
    }

    public static String versionLine() {
        return "Golem95 " + VERSION + " with OneLOop " + ONELOOP_VERSION + " (Java port " + JAVA_PORT_VERSION + ")";
    }

    /* ---------------------------------------------------------------- parallel engines */

    private static volatile ExecutorService pool;
    private static final ThreadLocal<GolemEngine> ENGINES = ThreadLocal.withInitial(GolemEngine::new);

    /** The workers, one per processor: each keeps its own engine. */
    static ExecutorService pool() {
        ExecutorService p = pool;
        if (p == null) {
            synchronized (Golem95.class) {
                p = pool;
                if (p == null) {
                    final AtomicInteger count = new AtomicInteger();
                    p = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors()), r -> {
                        final Thread t = new Thread(r, "golem95-" + count.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    });
                    pool = p;
                }
            }
        }
        return p;
    }

    public static int workers() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    /** This thread's engine, with these settings. */
    static GolemEngine engine(GolemEngine.Settings settings) {
        return ENGINES.get().configure(settings);
    }

    /** One answer of a batch: the value, or why there is none. */
    public record Outcome<T>(T value, RuntimeException error) {

        public boolean ok() {
            return error == null;
        }
    }

    /** Whether the calling thread is one of the workers. */
    public static boolean onWorker() {
        return Thread.currentThread().getName().startsWith("golem95-");
    }

    /**
     * Runs the tasks on the workers and waits for every one; a task that fails gives its exception. From a
     * worker itself they run in order, on it: a worker waiting on the pool it belongs to can leave none free.
     */
    public static <T> List<Outcome<T>> all(List<Callable<T>> tasks) {
        if (onWorker()) {
            final List<Outcome<T>> out = new ArrayList<>();
            for (Callable<T> c : tasks) {
                try {
                    out.add(new Outcome<>(c.call(), null));
                } catch (RuntimeException e) {
                    out.add(new Outcome<>(null, e));
                } catch (Exception e) {
                    out.add(new Outcome<>(null, new IllegalStateException(e)));
                }
            }
            return out;
        }
        final List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> c : tasks) {
            futures.add(pool().submit(c));
        }
        final List<Outcome<T>> out = new ArrayList<>();
        for (Future<T> f : futures) {
            try {
                out.add(new Outcome<>(f.get(), null));
            } catch (ExecutionException e) {
                final Throwable c = e.getCause();
                out.add(new Outcome<>(null, c instanceof RuntimeException r ? r : new IllegalStateException(c)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                out.add(new Outcome<>(null, new IllegalStateException("interrupted")));
            }
        }
        return out;
    }

    /** The value of a quantity for a kinematics, computed by a worker's engine. */
    public static Callable<GolemEngine.Value> task(Quantity q, Kinematics k, GolemEngine.Settings s) {
        return () -> q.on(engine(s).set(k));
    }

    /** The calling worker's engine, with these settings (one per thread, kept). */
    public static GolemEngine workerEngine(GolemEngine.Settings s) {
        return engine(s);
    }
}
