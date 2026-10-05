package com.sphere.core.hepmc3;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Reads with several threads at once (ReaderMT&lt;T, N&gt;): N readers of the
 * same file, reader i starting at event N-1-i and each skipping N-1 after
 * every event, so that a round reads N consecutive events in parallel. The
 * events are handed out in file order.
 */
public class ReaderMT extends Reader {

    private final int numberOfThreads;
    private boolean goTryCache = true;
    private final List<Reader> readers = new ArrayList<>();
    private final List<GenEvent> events = new ArrayList<>();
    private final List<Boolean> ok = new ArrayList<>();

    public ReaderMT(Path filename, Function<Path, ? extends Reader> factory, int numberOfThreads) {
        this.numberOfThreads = Math.max(1, numberOfThreads);
        for (int i = 0; i < this.numberOfThreads; ++i) {
            final Reader r = factory.apply(filename);
            readers.add(r);
            r.skip(this.numberOfThreads - 1 - i);
        }
    }

    /** From readers already open, all on the same input. */
    public ReaderMT(List<? extends Reader> given) {
        this.numberOfThreads = given.size();
        for (int i = 0; i < numberOfThreads; ++i) {
            final Reader r = given.get(i);
            readers.add(r);
            r.skip(numberOfThreads - 1 - i);
        }
    }

    /** Not implemented in HepMC3 either. */
    @Override
    public boolean skip(int n) {
        return false;
    }

    @Override
    public boolean readEvent(GenEvent evt) {
        if (!events.isEmpty()) {
            evt.assign(events.remove(events.size() - 1));
            ok.remove(ok.size() - 1);
            return true;
        }
        events.clear();
        ok.clear();
        goTryCache = true;
        final List<Thread> threads = new ArrayList<>(numberOfThreads);
        for (int i = 0; i < numberOfThreads; ++i) {
            events.add(new GenEvent(Units.MomentumUnit.GEV, Units.LengthUnit.MM));
            ok.add(true);
        }
        // the threads print where the caller prints
        final com.sphere.core.hepmc3.cxx.StdStreams streams = com.sphere.core.hepmc3.cxx.StdStreams.current();
        for (int i = 0; i < numberOfThreads; ++i) {
            final int k = i;
            final Reader r = readers.get(k);
            final Thread t = new Thread(() -> {
                try {
                    com.sphere.core.hepmc3.cxx.StdStreams.with(streams, () -> {
                        final boolean got = r.readEvent(events.get(k));
                        synchronized (ok) {
                            ok.set(k, got);
                        }
                        r.skip(numberOfThreads - 1);
                        if (r.failed()) r.close();
                        return null;
                    });
                } catch (Exception e) {
                    synchronized (ok) {
                        ok.set(k, false);
                    }
                }
            }, "hepmc3-readermt-" + k);
            threads.add(t);
            t.start();
        }
        for (Thread th : threads) {
            try {
                th.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        for (int i = events.size() - 1; i >= 0; i--) {
            if (!ok.get(i)) {
                events.remove(i);
                ok.remove(i);
            }
        }
        if (events.isEmpty()) {
            goTryCache = false;
            return false;
        }
        evt.assign(events.remove(events.size() - 1));
        ok.remove(ok.size() - 1);
        return true;
    }

    @Override
    public boolean failed() {
        for (Reader r : readers) if (r != null && !r.failed()) return false;
        if (!events.isEmpty()) return false;
        return !goTryCache;
    }

    @Override
    public void close() {
        for (Reader r : readers) if (r != null) r.close();
    }
}
