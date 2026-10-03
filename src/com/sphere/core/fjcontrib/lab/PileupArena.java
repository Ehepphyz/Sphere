package com.sphere.core.fjcontrib.lab;

import com.sphere.core.fastjet.AreaDefinition;
import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.ClusterSequenceArea;
import com.sphere.core.fastjet.GhostedAreaSpec;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Selector;
import com.sphere.core.fastjet.tools.GridMedianBackgroundEstimator;
import com.sphere.core.fastjet.tools.JetMedianBackgroundEstimator;
import com.sphere.core.fastjet.tools.Subtractor;
import com.sphere.core.fjcontrib.constituentsubtractor.ConstituentSubtractor;
import com.sphere.core.fjcontrib.constituentsubtractor.IterativeConstituentSubtractor;
import com.sphere.core.fjcontrib.jetcleanser.JetCleanser;
import com.sphere.core.fjcontrib.signalfree.SignalFreeBackgroundEstimator;
import com.sphere.core.fjcontrib.softkiller.SoftKiller;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Pileup mitigation methods against the truth, on the events at hand.
 *
 * <p>Each event is taken as the hard scatter, and minimum-bias pileup is
 * overlaid: mu vertices, each a Poisson number of soft particles uniform in
 * |y| &lt; 4 with an exponential pt spectrum, a fraction of them charged.
 * The truth is the jets of the hard event alone. Every method then corrects
 * the full event, its jets are matched to the true ones, and what is
 * measured is how far they land: the bias and spread of pt and mass, how
 * many jets were found, the time each method takes. The pileup densities of
 * the estimators are compared with the density actually overlaid.
 *
 * <p>Knowing which particles are charged and from which vertex is what the
 * methods using tracks need (charged hadron subtraction, jet cleansing);
 * here it is known exactly, as it would be with perfect tracking.
 */
public final class PileupArena {

    /** A method, and what it is. */
    public enum Method {
        NONE("no correction"),
        AREA("area-median subtraction, rho x A (grid rho)"),
        CHS_AREA("charged-hadron subtraction, then area subtraction"),
        CS("ConstituentSubtractor, event-wide"),
        ICS("IterativeConstituentSubtractor (0.2, 0.1)"),
        SOFTKILLER("SoftKiller, 0.4 grid"),
        CHS_SOFTKILLER("charged-hadron subtraction, then SoftKiller"),
        CLEANSER("JetCleanser, linear, trimmed 0.3 subjets");

        private final String label;

        Method(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * @param mu            pileup vertices per event
     * @param perVertex     mean number of particles per vertex in |y| &lt; 4
     * @param meanPt        mean pt of a pileup particle (GeV)
     * @param charged       the charged fraction
     * @param r             the anti-kt radius of the jets
     * @param ptmin         the true jets considered are above it
     * @param njets         and are the leading ones
     */
    public record Options(int mu, double perVertex, double meanPt, double charged, double r, double ptmin, int njets,
                          long seed) {
        public static Options defaults() {
            return new Options(60, 50, 0.55, 0.6, 0.4, 50, 2, 1);
        }
    }

    /** How one method did. */
    public record Score(Method method, int matched, int truth, double ptBias, double ptSpread, double massBias,
                        double massSpread, double millisPerEvent) {
        public double efficiency() {
            return truth == 0 ? Double.NaN : (double) matched / truth;
        }
    }

    /** How a density estimator did: its mean and spread relative to the density overlaid. */
    public record RhoScore(String estimator, double meanRatio, double spreadRatio) {
    }

    public record Result(List<Score> scores, List<RhoScore> rho, double trueRho, int events, Options options) {
    }

    private PileupArena() {
    }

    /** The overlaid pileup of one event; charged particles carry user index 1, neutral 0, per vertex. */
    public static List<PseudoJet> pileup(Options o, SplittableRandom r) {
        final List<PseudoJet> out = new ArrayList<>();
        for (int v = 0; v < o.mu(); v++) {
            final int n = poisson(o.perVertex(), r);
            for (int k = 0; k < n; k++) {
                final double pt = 0.1 + (o.meanPt() - 0.1) * -Math.log(1 - r.nextDouble());
                final PseudoJet p = PseudoJet.ptYPhiM(pt, -4 + 8 * r.nextDouble(), 2 * Math.PI * r.nextDouble(), 0.13957);
                p.setUserIndex(r.nextDouble() < o.charged() ? 1 : 0);
                out.add(p);
            }
        }
        return out;
    }

    private static int poisson(double mean, SplittableRandom r) {
        if (mean > 60) return Math.max(0, (int) Math.round(mean + Math.sqrt(mean)
            * Math.sqrt(-2 * Math.log(1 - r.nextDouble())) * Math.cos(2 * Math.PI * r.nextDouble())));
        final double limit = Math.exp(-mean);
        double p = r.nextDouble();
        int k = 0;
        while (p > limit) {
            p *= r.nextDouble();
            k++;
        }
        return k;
    }

    /**
     * Runs every method on every event. Serial: the area methods draw their
     * ghosts from FastJet's shared generator, which keeps the run
     * reproducible only in order.
     */
    public static Result run(List<List<PseudoJet>> hardEvents, Options o) {
        final Method[] methods = Method.values();
        final int nm = methods.length;
        final double[][] sumPt = new double[nm][2];
        final double[][] sumM = new double[nm][2];
        final int[] matched = new int[nm];
        final double[] nanos = new double[nm];
        int truthJets = 0;
        final List<double[]> rhoRatios = new ArrayList<>();
        final String[] estimators = {"grid median", "jet median", "signal-free"};
        double trueRhoSum = 0;

        final JetDefinition def = new JetDefinition(JetAlgorithm.ANTIKT, o.r());
        final SplittableRandom rnd = new SplittableRandom(o.seed());
        final double area = 8 * 2 * Math.PI;
        for (List<PseudoJet> hardIn : hardEvents) {
            final List<PseudoJet> hard = new ArrayList<>(hardIn.size());
            for (PseudoJet p : hardIn) {
                final PseudoJet c = p.copy();
                c.setUserIndex(rnd.nextDouble() < o.charged() ? 3 : 2); // 3: charged from the hard vertex
                hard.add(c);
            }
            final List<PseudoJet> pu = pileup(o, rnd);
            double puPt = 0;
            for (PseudoJet p : pu) puPt += p.pt();
            final double trueRho = puPt / area;
            trueRhoSum += trueRho;
            final List<PseudoJet> full = new ArrayList<>(hard);
            full.addAll(pu);
            final List<PseudoJet> fullEta = Selector.absRapMax(4.0).apply(full);

            final List<PseudoJet> truth = leading(new ClusterSequence(hard, def).inclusiveJets(o.ptmin() * 0.5), o.ptmin(), o.njets());
            truthJets += truth.size();

            // the density estimators
            final double[] rhos = new double[3];
            final GridMedianBackgroundEstimator grid = new GridMedianBackgroundEstimator(4.0, 0.55);
            grid.setParticles(fullEta);
            rhos[0] = grid.rho();
            final JetMedianBackgroundEstimator jm = new JetMedianBackgroundEstimator(Selector.absRapMax(3.6),
                new JetDefinition(JetAlgorithm.KT, 0.4),
                new AreaDefinition(AreaDefinition.AreaType.ACTIVE_EXPLICIT_GHOSTS, new GhostedAreaSpec(4.0, 1, 0.01)));
            jm.setParticles(fullEta);
            rhos[1] = jm.rho();
            final SignalFreeBackgroundEstimator sf = new SignalFreeBackgroundEstimator(4.0, 0.55);
            final List<PseudoJet> hardCharged = new ArrayList<>();
            for (PseudoJet p : fullEta) if (p.userIndex() == 3) hardCharged.add(p);
            sf.setParticles(fullEta, List.of(), -1, hardCharged);
            rhos[2] = sf.rho();
            rhoRatios.add(new double[]{rhos[0] / trueRho, rhos[1] / trueRho, rhos[2] / trueRho});

            for (int m = 0; m < nm; m++) {
                final long t0 = System.nanoTime();
                final List<PseudoJet> reco = reconstruct(methods[m], fullEta, grid, def, o);
                nanos[m] += System.nanoTime() - t0;
                for (PseudoJet t : truth) {
                    final PseudoJet match = nearest(reco, t, 0.5 * o.r());
                    if (match == null) continue;
                    matched[m]++;
                    final double dpt = (match.pt() - t.pt()) / t.pt();
                    final double dm = match.m() - t.m();
                    sumPt[m][0] += dpt;
                    sumPt[m][1] += dpt * dpt;
                    sumM[m][0] += dm;
                    sumM[m][1] += dm * dm;
                }
            }
        }
        final List<Score> scores = new ArrayList<>();
        for (int m = 0; m < nm; m++) {
            final int n = matched[m];
            final double ptMean = n == 0 ? Double.NaN : sumPt[m][0] / n;
            final double mMean = n == 0 ? Double.NaN : sumM[m][0] / n;
            scores.add(new Score(methods[m], n, truthJets, ptMean,
                n < 2 ? Double.NaN : Math.sqrt(Math.max(0, sumPt[m][1] / n - ptMean * ptMean)), mMean,
                n < 2 ? Double.NaN : Math.sqrt(Math.max(0, sumM[m][1] / n - mMean * mMean)),
                hardEvents.isEmpty() ? 0 : nanos[m] / 1e6 / hardEvents.size()));
        }
        final List<RhoScore> rho = new ArrayList<>();
        for (int k = 0; k < estimators.length; k++) {
            double s = 0;
            double s2 = 0;
            for (double[] x : rhoRatios) {
                s += x[k];
                s2 += x[k] * x[k];
            }
            final double mean = rhoRatios.isEmpty() ? Double.NaN : s / rhoRatios.size();
            rho.add(new RhoScore(estimators[k], mean,
                rhoRatios.size() < 2 ? Double.NaN : Math.sqrt(Math.max(0, s2 / rhoRatios.size() - mean * mean))));
        }
        return new Result(scores, rho, hardEvents.isEmpty() ? Double.NaN : trueRhoSum / hardEvents.size(),
            hardEvents.size(), o);
    }

    /** The jets a method finds in the full event, above half the truth threshold. */
    static List<PseudoJet> reconstruct(Method m, List<PseudoJet> full, GridMedianBackgroundEstimator grid,
                                       JetDefinition def, Options o) {
        final double cut = o.ptmin() * 0.5;
        return switch (m) {
            case NONE -> new ClusterSequence(full, def).inclusiveJets(cut);
            case AREA -> areaSubtracted(full, grid.rho(), def, cut);
            case CHS_AREA -> {
                final List<PseudoJet> chs = withoutChargedPileup(full);
                final GridMedianBackgroundEstimator neutral = new GridMedianBackgroundEstimator(4.0, 0.55);
                neutral.setParticles(chs);
                yield areaSubtracted(chs, neutral.rho(), def, cut);
            }
            case CS -> {
                final ConstituentSubtractor cs = new ConstituentSubtractor();
                cs.setBackgroundEstimator(grid);
                cs.setMaxEta(4.0);
                cs.setMaxDistance(0.3);
                cs.setAlpha(1);
                cs.setGhostArea(0.01);
                cs.initialize();
                yield new ClusterSequence(cs.subtractEvent(full), def).inclusiveJets(cut);
            }
            case ICS -> {
                final IterativeConstituentSubtractor ics = new IterativeConstituentSubtractor();
                ics.setMaxEta(4.0);
                ics.setParameters(List.of(0.2, 0.1), List.of(1.0, 1.0));
                ics.setGhostRemoval(true);
                ics.setGhostArea(0.01);
                ics.setBackgroundEstimator(grid);
                ics.initialize();
                yield new ClusterSequence(ics.subtractEvent(full), def).inclusiveJets(cut);
            }
            case SOFTKILLER -> new ClusterSequence(new SoftKiller(4.0, 0.4).apply(full).reducedEvent(), def).inclusiveJets(cut);
            case CHS_SOFTKILLER -> {
                final List<PseudoJet> chs = withoutChargedPileup(full);
                yield new ClusterSequence(new SoftKiller(4.0, 0.4).apply(chs).reducedEvent(), def).inclusiveJets(cut);
            }
            case CLEANSER -> cleansed(full, def, cut);
        };
    }

    private static List<PseudoJet> withoutChargedPileup(List<PseudoJet> full) {
        final List<PseudoJet> out = new ArrayList<>(full.size());
        for (PseudoJet p : full) if (p.userIndex() != 1) out.add(p);
        return out;
    }

    private static List<PseudoJet> areaSubtracted(List<PseudoJet> particles, double rho, JetDefinition def, double cut) {
        final ClusterSequenceArea csa = new ClusterSequenceArea(particles, def,
            new AreaDefinition(AreaDefinition.AreaType.ACTIVE_EXPLICIT_GHOSTS, new GhostedAreaSpec(4.5, 1, 0.01)));
        final Subtractor sub = new Subtractor(rho);
        final List<PseudoJet> out = new ArrayList<>();
        for (PseudoJet j : csa.inclusiveJets(cut * 0.5)) {
            final PseudoJet s = sub.result(j);
            if (s.pt() >= cut) out.add(s);
        }
        return out;
    }

    /** Jet cleansing, jet by jet: neutrals, charged from the hard vertex, charged from pileup. */
    private static List<PseudoJet> cleansed(List<PseudoJet> full, JetDefinition def, double cut) {
        final List<PseudoJet> neutral = new ArrayList<>();
        final List<PseudoJet> lv = new ArrayList<>();
        final List<PseudoJet> puCharged = new ArrayList<>();
        for (PseudoJet p : full) {
            switch (p.userIndex()) {
                case 3 -> lv.add(p);
                case 1 -> puCharged.add(p);
                default -> neutral.add(p);
            }
        }
        final JetCleanser cleanser = new JetCleanser(0.3, JetCleanser.CleansingMode.linear_cleansing,
            JetCleanser.InputMode.input_nc_separate);
        cleanser.setTrimming(0.0);
        cleanser.setLinearParameters();
        // the jets of the full event, each split into its neutrals, hard-vertex and pileup tracks
        final List<List<PseudoJet>> sets = JetCleanser.clusterSets(def, full, List.of(neutral, lv, puCharged), cut * 0.5);
        final List<PseudoJet> out = new ArrayList<>();
        for (int k = 0; k < sets.get(0).size(); k++) {
            final PseudoJet c = cleanser.result(members(sets.get(0).get(k)), members(sets.get(1).get(k)),
                members(sets.get(2).get(k)), true);
            if (c.pt() >= cut) out.add(c);
        }
        return out;
    }

    /** The particles of a composite jet; none for the empty placeholder. */
    private static List<PseudoJet> members(PseudoJet composite) {
        final List<PseudoJet> out = new ArrayList<>();
        for (PseudoJet p : composite.constituents()) if (!p.isZero()) out.add(p);
        return out;
    }

    private static List<PseudoJet> leading(List<PseudoJet> jets, double ptmin, int n) {
        final List<PseudoJet> out = new ArrayList<>();
        for (PseudoJet j : PseudoJet.sortedByPt(jets)) {
            if (j.pt() < ptmin || out.size() >= n) break;
            out.add(j);
        }
        return out;
    }

    private static PseudoJet nearest(List<PseudoJet> jets, PseudoJet t, double dmax) {
        PseudoJet best = null;
        double bestD = dmax * dmax;
        for (PseudoJet j : jets) {
            final double d = j.squaredDistance(t);
            if (d < bestD) {
                bestD = d;
                best = j;
            }
        }
        return best;
    }
}
