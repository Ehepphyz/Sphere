package com.sphere.core.fastjet.contrib;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * Many events clustered at once on several cores. Each event's clustering
 * is independent and deterministic, so the results are those of a serial
 * run, in the same order.
 *
 * Plugins are run serially: some keep state across events as the C++ ones
 * do (SISCone's random references and cache, PXCONE's banner), and their
 * results would otherwise depend on the scheduling.
 */
public final class EventBatch {

    private EventBatch() {
    }

    public static List<ClusterSequence> cluster(List<List<PseudoJet>> events, JetDefinition def, int threads) {
        return map(events, ev -> new ClusterSequence(ev, def), def.plugin() == null ? threads : 1);
    }

    /** Any per-event computation, in parallel, results in event order. */
    public static <R> List<R> map(List<List<PseudoJet>> events, Function<List<PseudoJet>, R> work, int threads) {
        if (threads <= 1 || events.size() < 2) {
            final List<R> out = new ArrayList<>(events.size());
            for (List<PseudoJet> ev : events) out.add(work.apply(ev));
            return out;
        }
        final ForkJoinPool pool = new ForkJoinPool(threads);
        try {
            return pool.submit(() -> IntStream.range(0, events.size()).parallel()
                .mapToObj(i -> work.apply(events.get(i))).toList()).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } finally {
            pool.shutdown();
        }
    }
}
