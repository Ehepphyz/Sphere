package com.sphere.core.fastjet.contrib;

import com.sphere.core.fastjet.Citations;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * An empirical infrared and collinear safety test of a jet definition on an
 * event: the hard jets must not change when soft particles are added
 * (infrared safety) nor when a particle is replaced by two collinear ones
 * sharing its momentum (collinear safety). Seeded cone algorithms (JetClu,
 * MidPoint, the iterative cones...) fail it on busy events; the kt family
 * and SISCone pass.
 *
 * Soft particles carry 1e-10 of the event's scalar pt, far above rounding
 * yet far below any physical scale; a hard jet "changes" if the jets above
 * ptmin differ in number, or one moves by more than 1e-7 relative in pt or
 * 1e-7 in rapidity or azimuth.
 */
public final class IrcSafetyCheck {

    private IrcSafetyCheck() {
    }

    /** The outcome: how many of the trials changed the hard jets, and the first such change. */
    public record Report(String jetDefinition, int irTrials, int irFailures, int collinearTrials,
                         int collinearFailures, String firstFailure) {
        public boolean infraredSafe() { return irFailures == 0; }
        public boolean collinearSafe() { return collinearFailures == 0; }

        @Override
        public String toString() {
            return jetDefinition + "\n  infrared : " + (infraredSafe() ? "safe" : "UNSAFE") + " (" + irFailures + "/"
                + irTrials + " trials changed the hard jets)\n  collinear: " + (collinearSafe() ? "safe" : "UNSAFE")
                + " (" + collinearFailures + "/" + collinearTrials + " trials changed the hard jets)"
                + (firstFailure.isEmpty() ? "" : "\n  first change: " + firstFailure);
        }
    }

    public static Report check(List<PseudoJet> event, JetDefinition def, double ptmin, int trials, long seed) {
        final SplittableRandom rnd = new SplittableRandom(seed);
        final List<PseudoJet> reference = hardJets(event, def, ptmin);
        double scalarPt = 0;
        double ymin = Double.MAX_VALUE;
        double ymax = -Double.MAX_VALUE;
        for (PseudoJet p : event) {
            scalarPt += p.pt();
            if (p.pt() > 0) {
                ymin = Math.min(ymin, p.rap());
                ymax = Math.max(ymax, p.rap());
            }
        }
        if (ymin > ymax) {
            ymin = -1;
            ymax = 1;
        }
        String first = "";
        int irFail = 0;
        int irTrials = trials;
        // the configurations that expose seeded cones: a soft particle at
        // the midpoint of each pair of hard jets closer than 2R + 1
        final double reach = 2 * def.R() + 1;
        for (int a = 0; a < reference.size(); a++) {
            for (int b = a + 1; b < reference.size(); b++) {
                final PseudoJet ja = reference.get(a);
                final PseudoJet jb = reference.get(b);
                if (ja.deltaR(jb) > reach) continue;
                final PseudoJet s = new PseudoJet();
                final double phi = ja.phi() + 0.5 * Kin.dphi(jb, ja).doubleValue();
                s.resetMomentumPtYPhiM(1e-10 * scalarPt, 0.5 * (ja.rap() + jb.rap()), phi, 0.0);
                final List<PseudoJet> soft = new ArrayList<>(event);
                soft.add(s);
                irTrials++;
                final String diff = compare(reference, hardJets(soft, def, ptmin));
                if (!diff.isEmpty()) {
                    irFail++;
                    if (first.isEmpty()) first = "a soft particle midway between hard jets " + a + " and " + b + ": " + diff;
                }
            }
        }
        for (int t = 0; t < trials; t++) {
            final List<PseudoJet> soft = new ArrayList<>(event);
            for (int k = 0; k < 20; k++) {
                final PseudoJet s = new PseudoJet();
                s.resetMomentumPtYPhiM(1e-10 * scalarPt / 20 * (0.5 + rnd.nextDouble()),
                    ymin + (ymax - ymin) * rnd.nextDouble(), 2 * Math.PI * rnd.nextDouble(), 0.0);
                soft.add(s);
            }
            final String diff = compare(reference, hardJets(soft, def, ptmin));
            if (!diff.isEmpty()) {
                irFail++;
                if (first.isEmpty()) first = "adding 20 soft particles: " + diff;
            }
        }
        int colFail = 0;
        final List<PseudoJet> sorted = PseudoJet.sortedByPt(event);
        int colTrials = trials;
        // the hardest particle, the seed of every seeded algorithm, in halves
        if (!sorted.isEmpty()) {
            final List<PseudoJet> split = new ArrayList<>(event);
            final PseudoJet top = sorted.get(0);
            for (int k = 0; k < split.size(); k++) {
                if (split.get(k) == top) {
                    split.set(k, top.times(0.5));
                    split.add(top.times(0.5));
                    break;
                }
            }
            colTrials++;
            final String diff = compare(reference, hardJets(split, def, ptmin));
            if (!diff.isEmpty()) {
                colFail++;
                if (first.isEmpty()) first = "the hardest particle split in two halves: " + diff;
            }
        }
        for (int t = 0; t < trials; t++) {
            final List<PseudoJet> split = new ArrayList<>(event);
            // alternately: one of the five hardest particles in two, or any
            // particle in up to eight equal pieces (which brings a seed below
            // a seed threshold: the classic collinear unsafety of seeded cones)
            final boolean hard = (t % 2 == 0);
            final PseudoJet victim = hard ? sorted.get(rnd.nextInt(Math.min(5, sorted.size())))
                                          : sorted.get(rnd.nextInt(sorted.size()));
            int pick = 0;
            for (int k = 0; k < split.size(); k++) {
                if (split.get(k) == victim) {
                    pick = k;
                    break;
                }
            }
            final PseudoJet p = split.get(pick);
            final String how;
            if (hard) {
                final double f = 0.1 + 0.8 * rnd.nextDouble();
                split.set(pick, p.times(f));
                split.add(p.times(1 - f));
                how = String.format(Locale.ROOT, "splitting a hard particle (pt %.4g) collinearly into %.2f + %.2f", p.pt(), f, 1 - f);
            } else {
                final int pieces = 2 + rnd.nextInt(7);
                split.set(pick, p.times(1.0 / pieces));
                for (int k = 1; k < pieces; k++) split.add(p.times(1.0 / pieces));
                how = String.format(Locale.ROOT, "splitting a particle (pt %.4g) collinearly into %d equal pieces", p.pt(), pieces);
            }
            final String diff = compare(reference, hardJets(split, def, ptmin));
            if (!diff.isEmpty()) {
                colFail++;
                if (first.isEmpty()) first = how + ": " + diff;
            }
        }
        return new Report(def.description(), irTrials, irFail, colTrials, colFail, first);
    }

    /** How a configuration came out. */
    public enum Status { SAFE, UNSAFE, FAILS }

    /** The result of one canonical configuration. */
    public record Probe(String name, Status status, String detail) {
        public boolean passed() { return status == Status.SAFE; }

        @Override
        public String toString() {
            return switch (status) {
                case SAFE -> "  safe   " + name + (detail.isEmpty() ? "" : " (" + detail + ")");
                case UNSAFE -> "  UNSAFE " + name + ": " + detail;
                case FAILS -> "  FAILS  " + name + ": " + detail;
            };
        }
    }

    private static PseudoJet particle(double pt, double y, double phi) {
        final PseudoJet p = new PseudoJet();
        p.resetMomentumPtYPhiM(pt, y, phi, 0.0);
        return p;
    }

    /**
     * The textbook configurations on which seeded cone algorithms fail
     * (G.P. Salam, G. Soyez, JHEP 05 (2007) 086, section 2):
     * two hard particles 1.5 R apart, with a soft one added between them
     * (infrared unsafety of cones with split-merge but no midpoints), and
     * three particles 0.9 R apart in pt order 100, 90, 80, with the hardest
     * split in two collinear halves (collinear unsafety of progressive
     * removal, which takes the hardest particle as the first seed).
     */
    public static List<Probe> probes(JetDefinition def) {
        Citations.use("irc");
        final double R = def.R();
        final List<Probe> out = new ArrayList<>();
        final List<PseudoJet> two = List.of(particle(100, 0, 0), particle(80, 0, 1.5 * R));
        final List<PseudoJet> twoSoft = new ArrayList<>(two);
        twoSoft.add(particle(1e-6, 0, 0.75 * R));
        out.add(probe("infrared: soft particle between two hard ones 1.5R apart", def, two, twoSoft));
        final PseudoJet a = particle(100, 0, 0);
        final List<PseudoJet> three = List.of(a, particle(90, 0, 0.9 * R), particle(80, 0, 1.8 * R));
        final List<PseudoJet> threeSplit = List.of(a.times(0.5), a.times(0.5), particle(90, 0, 0.9 * R),
            particle(80, 0, 1.8 * R));
        out.add(probe("collinear: hardest of three aligned particles split in halves", def, three, threeSplit));
        return out;
    }

    /**
     * One configuration. An algorithm that stops on it is not called safe or
     * unsafe but reported as failing, with its reason: a configuration an
     * experiment can meet and a clustering cannot process is a finding in
     * itself. When the algorithm has a robust variant (PxCone), that variant
     * is tried too and its verdict given alongside.
     */
    private static Probe probe(String name, JetDefinition def, List<PseudoJet> before, List<PseudoJet> after) {
        try {
            final String d = compare(hardJets(before, def, 1.0), hardJets(after, def, 1.0));
            return new Probe(name, d.isEmpty() ? Status.SAFE : Status.UNSAFE, d);
        } catch (RuntimeException failure) {
            final JetDefinition robust = robustVariant(def);
            String robustVerdict = "";
            if (robust != null) {
                try {
                    final String d = compare(hardJets(before, robust, 1.0), hardJets(after, robust, 1.0));
                    robustVerdict = "; in its robust mode it is " + (d.isEmpty() ? "safe" : "UNSAFE (" + d + ")");
                } catch (RuntimeException alsoFails) {
                    robustVerdict = "; its robust mode fails too: " + alsoFails.getMessage();
                }
            }
            return new Probe(name, Status.FAILS, failure.getMessage() + robustVerdict);
        }
    }

    /** The robust variant of an algorithm that has one, or null. */
    static JetDefinition robustVariant(JetDefinition def) {
        if (def.plugin() instanceof com.sphere.core.fastjet.plugins.PxConePlugin px && !px.robust()) {
            final JetDefinition r = new JetDefinition(new com.sphere.core.fastjet.plugins.PxConePlugin(px.coneRadius(),
                px.minJetEnergy(), px.overlapThreshold(), px.eSchemeJets(), px.mode()).setRobust(true));
            r.setPrecision(def.precision());
            return r;
        }
        return null;
    }

    private static List<PseudoJet> hardJets(List<PseudoJet> particles, JetDefinition def, double ptmin) {
        return PseudoJet.sortedByPt(new ClusterSequence(particles, def).inclusiveJets(ptmin));
    }

    private static String compare(List<PseudoJet> a, List<PseudoJet> b) {
        if (a.size() != b.size()) return a.size() + " hard jets became " + b.size();
        for (int i = 0; i < a.size(); i++) {
            final PseudoJet x = a.get(i);
            final PseudoJet y = b.get(i);
            final double dpt = Math.abs(x.pt() - y.pt()) / Math.max(x.pt(), 1e-300);
            final double dy = Math.abs(x.rap() - y.rap());
            double dphi = Math.abs(x.phi() - y.phi());
            if (dphi > Math.PI) dphi = 2 * Math.PI - dphi;
            if (dpt > 1e-7 || dy > 1e-7 || dphi > 1e-7) {
                return String.format(Locale.ROOT, "jet %d moved (pt %.6g -> %.6g, y %.6g -> %.6g)", i, x.pt(), y.pt(), x.rap(), y.rap());
            }
        }
        return "";
    }
}
