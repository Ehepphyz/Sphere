package com.sphere.core.fjcontrib.lab;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.Precision;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.cmpplugin.CMPPlugin;
import com.sphere.core.fjcontrib.flavorcone.FlavorConePlugin;
import com.sphere.core.fjcontrib.ghsalgo.GHSAlgo;
import com.sphere.core.fjcontrib.ifnplugin.FlavHistory;
import com.sphere.core.fjcontrib.ifnplugin.FlavInfo;
import com.sphere.core.fjcontrib.ifnplugin.FlavRecombiner;
import com.sphere.core.fjcontrib.ifnplugin.IFNPlugin;
import com.sphere.core.fjcontrib.sdfplugin.SDFlavourCalc;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The flavour of jets by every definition fjcontrib offers, side by side,
 * and put to the test.
 *
 * <p>Net flavour with anti-kt is the naive answer, and it is not infrared
 * safe: a soft quark and antiquark landing in two different jets flavour
 * both. IFN, CMP, GHS and SDF were each built to fix that. {@link #label}
 * gives the leading jets of an event with the label each definition
 * assigns; {@link #ircTest} adds soft flavoured pairs, and splits gluons
 * into collinear quark pairs, and counts which definitions change their
 * answer: the test of arXiv:2306.07314, on the events at hand.
 */
public final class FlavourLab {

    /** The definitions compared. */
    public enum Algo {
        NET("anti-kt net"), IFN("IFN"), CMP("CMP"), GHS("GHS"), SDF("SDF");

        private final String label;

        Algo(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * @param r     the jet radius (anti-kt, and each algorithm's)
     * @param ptmin the jets labelled are above it (GHS's fiducial cut too)
     * @param njets how many leading jets
     * @param only  a flavour 1..6 to keep alone (5 for b-tagging), 0 for all
     */
    public record Options(double r, double ptmin, int njets, int only, double ifnAlpha, double cmpA,
                          double ghsAlpha, double ghsOmega, double sdfBeta, double sdfZcut) {
        public static Options defaults() {
            return new Options(0.4, 20, 2, 0, 2.0, 0.1, 1.0, 2.0, 2.0, 0.1);
        }

        public Options withOnly(int f) {
            return new Options(r, ptmin, njets, f, ifnAlpha, cmpA, ghsAlpha, ghsOmega, sdfBeta, sdfZcut);
        }

        public Options withJets(int n, double pt) {
            return new Options(r, pt, n, only, ifnAlpha, cmpA, ghsAlpha, ghsOmega, sdfBeta, sdfZcut);
        }
    }

    /** A leading jet and its label under each definition (null where a definition found no matching jet). */
    public record Labelled(PseudoJet jet, FlavInfo[] labels) {
        public FlavInfo label(Algo a) {
            return labels[a.ordinal()];
        }
    }

    private FlavourLab() {
    }

    /** Whether any particle of the event carries a PDG code (an index other than FastJet's default -1). */
    public static boolean hasPdgCodes(List<PseudoJet> event) {
        for (PseudoJet p : event) if (p.userIndex() != -1) return true;
        return false;
    }

    /** Copies of the particles, each with a FlavHistory from its PDG code (only one flavour kept if asked). */
    public static List<PseudoJet> flavoured(List<PseudoJet> event, int only) {
        final List<PseudoJet> out = new ArrayList<>(event.size());
        for (PseudoJet p : event) {
            final PseudoJet c = p.copy();
            FlavInfo f = FlavInfo.fromUserIndex(p);
            if (only > 0) f = f.onlyFlav(only);
            c.setUserInfo(new FlavHistory(f));
            out.add(c);
        }
        return out;
    }

    /** The leading jets of one event, labelled by each definition. */
    public static List<Labelled> label(List<PseudoJet> event, Options o) {
        final List<PseudoJet> particles = flavoured(event, o.only());
        final FlavRecombiner fr = new FlavRecombiner();
        final JetDefinition base = new JetDefinition(JetAlgorithm.ANTIKT, o.r());
        base.setRecombiner(fr);
        base.setPrecision(Precision.DOUBLE);

        final List<PseudoJet> baseAll = PseudoJet.sortedByPt(new ClusterSequence(particles, base).inclusiveJets());
        final List<PseudoJet> baseJets = new ArrayList<>();
        for (PseudoJet j : baseAll) if (j.pt() >= o.ptmin() && baseJets.size() < o.njets()) baseJets.add(j);

        final List<PseudoJet> ifn = PseudoJet.sortedByPt(new ClusterSequence(particles,
            new JetDefinition(new IFNPlugin(base, o.ifnAlpha()))).inclusiveJets());
        final JetDefinition cmpDef = new JetDefinition(new CMPPlugin(o.r(), o.cmpA()));
        cmpDef.setRecombiner(fr);
        final List<PseudoJet> cmp = PseudoJet.sortedByPt(new ClusterSequence(particles, cmpDef).inclusiveJets());
        final List<PseudoJet> ghs = GHSAlgo.runGHS(baseAll, o.ptmin(), o.ghsAlpha(), o.ghsOmega(), fr);
        final SDFlavourCalc sdf = new SDFlavourCalc(o.sdfBeta(), o.sdfZcut(), o.r());

        final List<Labelled> out = new ArrayList<>();
        for (PseudoJet j : baseJets) {
            final FlavInfo[] l = new FlavInfo[Algo.values().length];
            l[Algo.NET.ordinal()] = FlavHistory.currentFlavourOf(j);
            l[Algo.IFN.ordinal()] = labelOfNearest(ifn, j, o.r());
            l[Algo.CMP.ordinal()] = labelOfNearest(cmp, j, o.r());
            l[Algo.GHS.ordinal()] = labelOfNearest(ghs, j, o.r());
            l[Algo.SDF.ordinal()] = sdf.flavourOf(j);
            out.add(new Labelled(j, l));
        }
        return out;
    }

    /** The label of the jet of the list nearest to j, within R/2; null if none. */
    private static FlavInfo labelOfNearest(List<PseudoJet> jets, PseudoJet j, double r) {
        PseudoJet best = null;
        double bestD = 0.25 * r * r;
        for (PseudoJet c : jets) {
            final double d = c.squaredDistance(j);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best == null ? null : FlavHistory.currentFlavourOf(best);
    }

    /* ------------------------------------------------------------------ */
    /* Across a sample                                                     */
    /* ------------------------------------------------------------------ */

    /** How often each definition flavours a jet, and how often each pair of definitions agree. */
    public record Comparison(int events, int jets, int[] flavoured, int[][] agree, int[][] both, double millis) {
        public double agreement(Algo a, Algo b) {
            final int n = both[a.ordinal()][b.ordinal()];
            return n == 0 ? Double.NaN : (double) agree[a.ordinal()][b.ordinal()] / n;
        }
    }

    public static Comparison compare(List<List<PseudoJet>> events, Options o, int threads) {
        final long t0 = System.nanoTime();
        final List<List<Labelled>> all = Parallel.map(events.size(), e -> label(events.get(e), o), threads);
        final int n = Algo.values().length;
        final int[] flavoured = new int[n];
        final int[][] agree = new int[n][n];
        final int[][] both = new int[n][n];
        int jets = 0;
        for (List<Labelled> ev : all) {
            for (Labelled l : ev) {
                jets++;
                for (int a = 0; a < n; a++) {
                    if (l.labels()[a] != null && !l.labels()[a].isFlavourless()) flavoured[a]++;
                    for (int b = 0; b < n; b++) {
                        if (l.labels()[a] == null || l.labels()[b] == null) continue;
                        both[a][b]++;
                        if (sameFlavour(l.labels()[a], l.labels()[b])) agree[a][b]++;
                    }
                }
            }
        }
        return new Comparison(events.size(), jets, flavoured, agree, both, (System.nanoTime() - t0) / 1e6);
    }

    /** The same net content of the six flavours (flags aside). */
    static boolean sameFlavour(FlavInfo a, FlavInfo b) {
        for (int i = 1; i <= 6; i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    /* ------------------------------------------------------------------ */
    /* Infrared and collinear safety                                       */
    /* ------------------------------------------------------------------ */

    /**
     * What adding soft pairs, and splitting gluons collinearly, did to each
     * definition's labels, at each scale: [scale][definition] counts of trials
     * where a label changed.
     */
    public record IrcResult(int events, int trials, double[] epsilons, int[][] softChanged, int[][] collinearChanged,
                            double millis) {
        public double softRate(int scale, Algo a) {
            return trials == 0 ? Double.NaN : (double) softChanged[scale][a.ordinal()] / trials;
        }

        public double collinearRate(int scale, Algo a) {
            return trials == 0 ? Double.NaN : (double) collinearChanged[scale][a.ordinal()] / trials;
        }

        /** The rates at the smallest scale, the limit that decides safety. */
        public double softRate(Algo a) {
            return softRate(epsilons.length - 1, a);
        }

        public double collinearRate(Algo a) {
            return collinearRate(epsilons.length - 1, a);
        }

        /**
         * Safe in this test: nothing changes at the smallest scale. Changes
         * that are there at the larger scale only are power corrections that
         * vanish with it, which is what infrared and collinear safety allows.
         */
        public boolean safe(Algo a) {
            return softRate(a) == 0 && collinearRate(a) == 0;
        }

        /** Changes at the larger scale that vanished at the smaller one. */
        public boolean vanishing(Algo a) {
            boolean larger = false;
            for (int s = 0; s < epsilons.length - 1; s++) larger |= softRate(s, a) > 0 || collinearRate(s, a) > 0;
            return larger && safe(a);
        }
    }

    /**
     * For each event and trial: a quark and an antiquark of pt epsilon x the
     * leading jet's, each at its own random place (the configuration that
     * spoils net flavour), and separately the hardest gluon replaced by a
     * quark pair epsilon apart in rapidity. Each trial is repeated at every
     * scale with the same places, so that the scales differ by epsilon alone:
     * infrared and collinear safety is a statement about the limit, and a
     * change that vanishes as epsilon does is allowed, one that persists is
     * not.
     */
    public static IrcResult ircTest(List<List<PseudoJet>> events, Options o, int trials, double[] epsilons, long seed,
                                    int threads) {
        final long t0 = System.nanoTime();
        final int n = Algo.values().length;
        final int ns = epsilons.length;
        final List<int[][]> per = Parallel.map(events.size(), e -> {
            final int[][] changed = new int[2 * ns + 1][n];
            final List<PseudoJet> ev = events.get(e);
            final List<Labelled> ref = label(ev, o);
            if (ref.isEmpty()) return changed;
            final double lead = ref.get(0).jet().pt();
            final SplittableRandom r = new SplittableRandom(seed * 1000003L + e);
            for (int t = 0; t < trials; t++) {
                final int q = o.only() > 0 ? o.only() : 1 + r.nextInt(5);
                final double f1 = 0.5 + r.nextDouble();
                final double y1 = -2.5 + 5 * r.nextDouble();
                final double p1 = 2 * Math.PI * r.nextDouble();
                final double f2 = 0.5 + r.nextDouble();
                final double y2 = -2.5 + 5 * r.nextDouble();
                final double p2 = 2 * Math.PI * r.nextDouble();
                final double z = 0.2 + 0.6 * r.nextDouble();
                for (int s = 0; s < ns; s++) {
                    final double scale = lead * epsilons[s];
                    final List<PseudoJet> soft = new ArrayList<>(ev);
                    soft.add(masslessPdg(scale * f1, y1, p1, q));
                    soft.add(masslessPdg(scale * f2, y2, p2, -q));
                    countChanges(ref, label(soft, o), changed[s]);
                    final List<PseudoJet> split = splitHardestGluon(ev, q, z, epsilons[s]);
                    if (split != null) countChanges(ref, label(split, o), changed[ns + s]);
                }
                changed[2 * ns][0]++;
            }
            return changed;
        }, threads);
        final int[][] soft = new int[ns][n];
        final int[][] coll = new int[ns][n];
        int done = 0;
        for (int[][] p : per) {
            for (int s = 0; s < ns; s++) {
                for (int a = 0; a < n; a++) {
                    soft[s][a] += p[s][a];
                    coll[s][a] += p[ns + s][a];
                }
            }
            done += p[2 * ns][0];
        }
        return new IrcResult(events.size(), done, epsilons.clone(), soft, coll, (System.nanoTime() - t0) / 1e6);
    }

    /** Adds 1 to each definition that labels some leading jet otherwise than the reference did. */
    private static void countChanges(List<Labelled> ref, List<Labelled> now, int[] changed) {
        for (Algo a : Algo.values()) {
            boolean differs = ref.size() != now.size();
            for (int k = 0; !differs && k < ref.size(); k++) {
                final FlavInfo x = ref.get(k).label(a);
                final FlavInfo y = now.get(k).label(a);
                differs = (x == null) != (y == null) || (x != null && !sameFlavour(x, y));
            }
            if (differs) changed[a.ordinal()]++;
        }
    }

    /** The event with its hardest gluon split into a quark pair (z, 1-z) delta apart in rapidity; null if it has no gluon. */
    private static List<PseudoJet> splitHardestGluon(List<PseudoJet> ev, int q, double z, double delta) {
        int hardest = -1;
        for (int i = 0; i < ev.size(); i++) {
            if (ev.get(i).userIndex() == 21 && (hardest < 0 || ev.get(i).pt() > ev.get(hardest).pt())) hardest = i;
        }
        if (hardest < 0) return null;
        final PseudoJet g = ev.get(hardest);
        final List<PseudoJet> out = new ArrayList<>(ev);
        out.remove(hardest);
        out.add(masslessPdg(z * g.pt(), g.rap() + delta, g.phi(), q));
        out.add(masslessPdg((1 - z) * g.pt(), g.rap() - delta, g.phi(), -q));
        return out;
    }

    private static PseudoJet masslessPdg(double pt, double y, double phi, int pdg) {
        final PseudoJet p = PseudoJet.ptYPhiM(pt, y, phi, 0.0);
        p.setUserIndex(pdg);
        return p;
    }

    /* ------------------------------------------------------------------ */
    /* FlavorCone                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * FlavorCone jets around the particles of flavour f above seedPt: each
     * particle within rcut of a seed goes to the nearest one.
     */
    public static List<PseudoJet> flavorCone(List<PseudoJet> event, int flavour, double seedPt, double rcut) {
        final List<PseudoJet> seeds = new ArrayList<>();
        for (PseudoJet p : event) {
            if (p.pt() >= seedPt && FlavInfo.fromUserIndex(p).get(flavour) != 0) seeds.add(p);
        }
        if (seeds.isEmpty()) return List.of();
        return PseudoJet.sortedByPt(new ClusterSequence(event, new JetDefinition(new FlavorConePlugin(seeds, rcut)))
            .inclusiveJets());
    }
}
