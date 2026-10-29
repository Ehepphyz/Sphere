package com.sphere.core.clhep.random;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The numberOfEngines counters CLHEP keeps per engine kind (the seeds of default-made engines), all known
 * here so a fresh program can be started again: {@link ProcessState#restart}.
 */
final class EngineCounters {

    private static final List<AtomicInteger> ALL = new CopyOnWriteArrayList<>();

    private EngineCounters() {
    }

    /** A counter starting at 0, as the C++ static one does. */
    static AtomicInteger counter() {
        final AtomicInteger c = new AtomicInteger();
        ALL.add(c);
        return c;
    }

    static void reset() {
        for (AtomicInteger c : ALL) c.set(0);
    }
}
