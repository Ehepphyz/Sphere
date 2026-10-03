package com.sphere.core.fjcontrib.lab;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Observables measured on every event: the leading jets of each event, the
 * value of each observable for each of them, and the event observables once
 * per event. Events are measured on several cores when that cannot change
 * a single bit of the answer: no observable draws random numbers, and the
 * jet algorithm keeps no state between events (native algorithms and the
 * fjcontrib plugins); otherwise in order, on one thread.
 */
public final class Measurement {

    private Measurement() {
    }

    /** One jet of one event (jet = -1: an event without jets, for its event observables). */
    public record Row(int event, int jet, double weight, double pt, double rap, double phi, double[] values) {
    }

    /** Everything measured, with how. */
    public record Table(List<Observables.Observable> observables, List<Row> rows, int events, int njets, double ptmin,
                        String definition, double millis, int threads, int failures, String firstFailure) {

        /** The values of observable k over the rows (event observables once per event). */
        public double[] column(int k) {
            final boolean eventScope = observables.get(k).scope() == Observables.Scope.EVENT;
            final List<Double> out = new ArrayList<>(rows.size());
            for (Row r : rows) {
                if (eventScope && r.jet() > 0) continue;
                if (!eventScope && r.jet() < 0) continue;
                final double v = r.values()[k];
                if (!Double.isNaN(v)) out.add(v);
            }
            return out.stream().mapToDouble(Double::doubleValue).toArray();
        }

        /** The weights matching {@link #column}. */
        public double[] weights(int k) {
            final boolean eventScope = observables.get(k).scope() == Observables.Scope.EVENT;
            final List<Double> out = new ArrayList<>(rows.size());
            for (Row r : rows) {
                if (eventScope && r.jet() > 0) continue;
                if (!eventScope && r.jet() < 0) continue;
                if (!Double.isNaN(r.values()[k])) out.add(r.weight());
            }
            return out.stream().mapToDouble(Double::doubleValue).toArray();
        }

        public int index(String label) {
            for (int k = 0; k < observables.size(); k++) {
                if (observables.get(k).label().equals(label) || observables.get(k).name().equals(label)) return k;
            }
            return -1;
        }
    }

    /** Whether events can be measured in parallel without changing a bit of the answer. */
    public static boolean parallelSafe(JetDefinition def, List<Observables.Observable> obs) {
        for (Observables.Observable o : obs) if (!o.deterministic()) return false;
        if (def.plugin() == null) return true;
        // fjcontrib's plugins keep nothing from one event to the next
        return def.plugin().getClass().getName().startsWith("com.sphere.core.fjcontrib.");
    }

    /**
     * @param njets the leading jets measured per event
     * @param ptmin the jets considered are above it
     */
    public static Table measure(List<List<PseudoJet>> events, List<Double> weights, JetDefinition def,
                                List<Observables.Observable> obs, int njets, double ptmin, int threads) {
        final boolean anyJet = obs.stream().anyMatch(o -> o.scope() == Observables.Scope.JET);
        final boolean anyEvent = obs.stream().anyMatch(o -> o.scope() == Observables.Scope.EVENT);
        final int used = parallelSafe(def, obs) ? Math.max(1, threads) : 1;
        final AtomicInteger failures = new AtomicInteger();
        final AtomicReference<String> first = new AtomicReference<>();
        final long t0 = System.nanoTime();
        final List<List<Row>> perEvent = Parallel.map(events.size(), e -> {
            final List<PseudoJet> ev = events.get(e);
            final double w = weights != null && e < weights.size() ? weights.get(e) : 1.0;
            final List<Observables.Evaluator> ev8 = new ArrayList<>(obs.size());
            for (Observables.Observable o : obs) ev8.add(o.evaluator());
            final Observables.Context ctx = new Observables.Context(ev, def.R());
            final double[] eventValues = new double[obs.size()];
            java.util.Arrays.fill(eventValues, Double.NaN);
            for (int k = 0; k < obs.size(); k++) {
                if (obs.get(k).scope() != Observables.Scope.EVENT) continue;
                try {
                    eventValues[k] = ev8.get(k).event(ctx);
                } catch (RuntimeException ex) {
                    fail(failures, first, obs.get(k).label(), e, ex);
                }
            }
            final List<Row> rows = new ArrayList<>();
            List<PseudoJet> jets = List.of();
            if (anyJet || !anyEvent) {
                final ClusterSequence cs = new ClusterSequence(ev, def);
                final List<PseudoJet> all = PseudoJet.sortedByPt(cs.inclusiveJets(ptmin));
                jets = all.subList(0, Math.min(njets, all.size()));
            }
            for (int j = 0; j < jets.size(); j++) {
                final PseudoJet jet = jets.get(j);
                final double[] v = eventValues.clone();
                for (int k = 0; k < obs.size(); k++) {
                    if (obs.get(k).scope() != Observables.Scope.JET) continue;
                    try {
                        v[k] = ev8.get(k).jet(jet, ctx);
                    } catch (RuntimeException ex) {
                        fail(failures, first, obs.get(k).label(), e, ex);
                    }
                }
                rows.add(new Row(e, j, w, jet.pt(), jet.rap(), jet.phi(), v));
            }
            if (jets.isEmpty() && anyEvent) rows.add(new Row(e, -1, w, Double.NaN, Double.NaN, Double.NaN, eventValues));
            return rows;
        }, used);
        final List<Row> rows = new ArrayList<>();
        for (List<Row> r : perEvent) rows.addAll(r);
        return new Table(List.copyOf(obs), rows, events.size(), njets, ptmin, def.description(),
            (System.nanoTime() - t0) / 1e6, used, failures.get(), first.get());
    }

    private static void fail(AtomicInteger failures, AtomicReference<String> first, String label, int event,
                             RuntimeException ex) {
        failures.incrementAndGet();
        first.compareAndSet(null, label + " on event " + event + ": " + ex.getMessage());
    }
}
