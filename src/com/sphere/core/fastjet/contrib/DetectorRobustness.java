package com.sphere.core.fastjet.contrib;

import com.sphere.core.fastjet.AreaDefinition;
import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.ClusterSequenceArea;
import com.sphere.core.fastjet.GhostedAreaSpec;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.tools.GridMedianBackgroundEstimator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * How a jet definition behaves where the theory of jet algorithms says
 * nothing and the experiment lives: the event as a calorimeter sees it, with
 * cells, noise, thresholds and negative energies; under pileup, with and
 * without area subtraction; and with the inputs no calculation produces but
 * a reconstruction does (a zero or negative energy, a track along the beam,
 * a tachyonic four-vector, a duplicated object, a NaN).
 *
 * Infrared and collinear safety are properties at infinite resolution; an
 * experiment measures through finite cells and thresholds, so a formally
 * unsafe algorithm can be stable at detector level and a safe one sensitive
 * to noise. These tests measure it on the event given.
 */
public final class DetectorRobustness {

    private DetectorRobustness() {
    }

    /** The user index the odd input is marked with, to be found again in the jets. */
    private static final int MARKER = 987654321;

    /** The settings of the detector model. */
    public record Detector(double cellEta, double cellPhi, double etaMax, double noiseSigma, double threshold,
                           double stochastic, double constant, double pileupRho, double pileupMeanPt,
                           double pileupRapMax, long seed) {
        /** An LHC-like calorimeter: 0.1 x 0.1 cells to |eta| 4.9, 0.2 GeV noise cut at 2 sigma,
         *  resolution 50%/sqrt(E) + 3%, pileup of 20 GeV per unit area. */
        public static Detector lhcLike() {
            return new Detector(0.1, 2 * Math.PI / 64, 4.9, 0.2, 0.4, 0.5, 0.03, 20.0, 0.5, 3.0, 12345);
        }
    }

    /** One test's outcome on the hard jets (pt above ptmin) of the event. */
    public record Result(String test, int referenceJets, int matched, double meanShift, double maxShift,
                         String verdict) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "  %-44s %2d/%2d jets matched, <|dpt|/pt> = %8.3g, max = %8.3g  %s",
                test, matched, referenceJets, meanShift, maxShift, verdict);
        }
    }

    public static List<Result> run(List<PseudoJet> event, JetDefinition def, double ptmin, Detector d) {
        final List<Result> out = new ArrayList<>();
        final List<PseudoJet> ref;
        try {
            ref = hard(event, def, ptmin);
        } catch (RuntimeException e) {
            out.add(new Result("particle level", 0, 0, 0, 0, "FAILS: " + e.getMessage()));
            return out;
        }
        final SplittableRandom rnd = new SplittableRandom(d.seed());

        // 1. calorimeter granularity alone
        final List<PseudoJet> cells = towers(event, d, null, 0.0, rnd, false);
        out.add(compare("calorimeter cells " + fmt(d.cellEta()) + " x " + fmt(d.cellPhi()), ref, def, cells, ptmin));

        // 2. cells with energy resolution, noise (both signs) and a threshold
        final List<PseudoJet> noisy = towers(event, d, rnd, d.noiseSigma(), rnd, true);
        final List<PseudoJet> thresholded = new ArrayList<>();
        for (PseudoJet c : noisy) if (Math.abs(c.E()) > d.threshold()) thresholded.add(c);
        out.add(compare("+ resolution, noise, |E| > " + fmt(d.threshold()) + " GeV (with E<0 cells)", ref, def,
            thresholded, ptmin));
        final List<PseudoJet> positive = new ArrayList<>();
        for (PseudoJet c : thresholded) if (c.E() > 0) positive.add(c);
        out.add(compare("  same, negative-energy cells removed", ref, def, positive, ptmin));

        // 3. pileup, then pileup subtracted with rho and jet areas
        final List<PseudoJet> pu = new ArrayList<>(event);
        final double area = 2 * d.pileupRapMax() * 2 * Math.PI;
        final int npu = (int) Math.round(d.pileupRho() * area / d.pileupMeanPt());
        for (int k = 0; k < npu; k++) {
            final PseudoJet p = new PseudoJet();
            p.resetMomentumPtYPhiM(-d.pileupMeanPt() * Math.log(1 - rnd.nextDouble()),
                d.pileupRapMax() * (2 * rnd.nextDouble() - 1), 2 * Math.PI * rnd.nextDouble(), 0.0);
            pu.add(p);
        }
        out.add(compare("pileup rho = " + fmt(d.pileupRho()) + " GeV/area (" + npu + " particles)", ref, def, pu, ptmin));
        out.add(subtracted(ref, def, pu, ptmin, d));

        // 4. inputs a reconstruction produces and a calculation does not
        final PseudoJet hardest = PseudoJet.sortedByPt(event).get(0);
        final PseudoJet negative = new PseudoJet(-0.3 * Math.cos(1.0), -0.3 * Math.sin(1.0), -0.2, -0.5);
        final Object[][] odd = {
            {"a zero four-vector", new PseudoJet(0, 0, 0, 0)},
            {"a particle along the beam (|pz| = E)", new PseudoJet(0, 0, 250, 250)},
            {"a negative-energy cell (E = -0.5 GeV)", negative},
            {"a tachyonic four-vector (m^2 < 0)", new PseudoJet(3, 0, 0, 2)},
            {"a duplicated hardest particle", hardest.copy()},
            {"a NaN four-vector", new PseudoJet(Double.NaN, 0, 0, Double.NaN)}};
        for (Object[] o : odd) {
            final List<PseudoJet> in = new ArrayList<>(event);
            final PseudoJet marked = ((PseudoJet) o[1]).copy();
            marked.setUserIndex(MARKER);
            in.add(marked);
            out.add(compare("input with " + o[0], ref, def, in, ptmin));
        }
        return out;
    }

    /** The event as calorimeter cells (massless, at the cell centre), optionally smeared and noisy. */
    static List<PseudoJet> towers(List<PseudoJet> event, Detector d, SplittableRandom smear, double noise,
                                  SplittableRandom rnd, boolean everyCell) {
        final int neta = (int) Math.ceil(2 * d.etaMax() / d.cellEta());
        final int nphi = (int) Math.round(2 * Math.PI / d.cellPhi());
        final double[] e = new double[neta * nphi];
        for (PseudoJet p : event) {
            final double eta = p.eta();
            if (!(Math.abs(eta) < d.etaMax())) continue;
            final int ie = (int) ((eta + d.etaMax()) / d.cellEta());
            final int ip = (int) (p.phi() / d.cellPhi()) % nphi;
            e[ie * nphi + ip] += p.E();
        }
        final List<PseudoJet> out = new ArrayList<>();
        for (int ie = 0; ie < neta; ie++) {
            final double eta = -d.etaMax() + (ie + 0.5) * d.cellEta();
            for (int ip = 0; ip < nphi; ip++) {
                double en = e[ie * nphi + ip];
                if (smear != null && en > 0) {
                    final double sigma = Math.hypot(d.stochastic() * Math.sqrt(en), d.constant() * en);
                    en += sigma * gauss(smear);
                }
                if (noise > 0) en += noise * gauss(rnd);
                if (en == 0 || (!everyCell && en <= 0)) continue;
                final double phi = (ip + 0.5) * d.cellPhi();
                final double pt = en / Math.cosh(eta);
                out.add(new PseudoJet(pt * Math.cos(phi), pt * Math.sin(phi), pt * Math.sinh(eta), en));
            }
        }
        return out;
    }

    private static double gauss(SplittableRandom r) {
        final double u = 1 - r.nextDouble();
        return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * r.nextDouble());
    }

    private static Result subtracted(List<PseudoJet> ref, JetDefinition def, List<PseudoJet> pu, double ptmin,
                                     Detector d) {
        final String name = "  same, subtracted with rho x area";
        try {
            final GridMedianBackgroundEstimator bge = new GridMedianBackgroundEstimator(d.pileupRapMax(), 0.55);
            bge.setParticles(pu);
            final double rho = bge.rho();
            final AreaDefinition ad = new AreaDefinition(AreaDefinition.AreaType.ACTIVE_EXPLICIT_GHOSTS,
                new GhostedAreaSpec(d.pileupRapMax() + def.R(), 1, 0.02));
            final List<PseudoJet> jets = runWithTimeout(() -> new ClusterSequenceArea(pu, def, ad).inclusiveJets(0));
            final List<PseudoJet> sub = new ArrayList<>();
            for (PseudoJet j : jets) {
                final double pt = j.pt() - rho * j.area();
                if (pt > 0.5 * ptmin) {
                    final PseudoJet s = new PseudoJet();
                    s.resetMomentumPtYPhiM(pt, j.rap(), j.phi(), 0.0);
                    sub.add(s);
                }
            }
            final Result r = match(name + String.format(Locale.ROOT, " (rho = %.3g)", rho), ref, sub);
            return r;
        } catch (RuntimeException e) {
            return new Result(name, ref.size(), 0, 0, 0, "FAILS: " + e.getMessage());
        }
    }

    private static Result compare(String test, List<PseudoJet> ref, JetDefinition def, List<PseudoJet> in,
                                  double ptmin) {
        try {
            // every final jet, those inclusive_jets would drop (a NaN pt) included
            final List<PseudoJet> all = runWithTimeout(() -> finalJets(new ClusterSequence(in, def)));
            final List<PseudoJet> jets = new ArrayList<>();
            for (PseudoJet j : all) if (j.pt() > 0.5 * ptmin) jets.add(j);
            final Result r = match(test, ref, jets);
            boolean hasMarker = false;
            for (PseudoJet p : in) hasMarker |= p.userIndex() == MARKER;
            final String fate = hasMarker ? fate(all, ptmin) : "";
            return fate.isEmpty() ? r : new Result(r.test(), r.referenceJets(), r.matched(), r.meanShift(),
                r.maxShift(), r.verdict() + "; " + fate);
        } catch (RuntimeException e) {
            final JetDefinition robust = IrcSafetyCheck.robustVariant(def);
            if (robust != null) {
                try {
                    final List<PseudoJet> jets = runWithTimeout(() -> new ClusterSequence(in, robust).inclusiveJets(0.5 * ptmin));
                    final Result r = match(test, ref, jets);
                    return new Result(r.test(), r.referenceJets(), r.matched(), r.meanShift(), r.maxShift(),
                        r.verdict() + " [robust mode; default fails: " + e.getMessage() + "]");
                } catch (RuntimeException ignored) {
                    // report the original failure
                }
            }
            return new Result(test, ref.size(), 0, 0, 0, "FAILS: " + e.getMessage());
        }
    }

    /** Every jet the clustering ended with (its beam recombinations), whatever its pt, NaN included. */
    static List<PseudoJet> finalJets(ClusterSequence cs) {
        final List<ClusterSequence.HistoryElement> h = cs.history();
        final List<PseudoJet> out = new ArrayList<>();
        for (ClusterSequence.HistoryElement e : h) {
            if (e.parent2() == ClusterSequence.BEAM_JET) out.add(cs.jet(h.get(e.parent1()).jetpIndex()));
        }
        return out;
    }

    /** Where the marked odd input went: into a hard jet, a jet of its own, or nowhere. */
    private static String fate(List<PseudoJet> jets, double ptmin) {
        boolean marked = false;
        for (PseudoJet j : jets) {
            if (!j.hasConstituents()) continue;
            for (PseudoJet c : j.constituents()) {
                if (c.userIndex() != MARKER) continue;
                marked = true;
                final boolean bad = Double.isNaN(j.E()) || Double.isNaN(j.px());
                if (j.constituents().size() == 1) {
                    return bad ? "it forms a NaN jet of its own (which inclusive_jets silently drops)"
                               : "it forms a jet of its own";
                }
                if (bad) return "absorbed into a jet it turns into NaN (which inclusive_jets then drops)";
                return j.pt() > ptmin ? "absorbed into a hard jet" : "absorbed into a soft jet";
            }
        }
        return marked ? "" : "it is in no jet (dropped by the algorithm)";
    }

    /** Each reference jet matched to the nearest test jet within R/2 in (y, phi). */
    private static Result match(String test, List<PseudoJet> ref, List<PseudoJet> jets) {
        int matched = 0;
        double sum = 0;
        double max = 0;
        for (PseudoJet r : ref) {
            PseudoJet best = null;
            double bestD = 0.2;
            for (PseudoJet j : jets) {
                if (!(j.pt() > 0) || Double.isNaN(j.rap())) continue;
                final double dr2 = r.plainDistance(j);
                if (dr2 < bestD) {
                    bestD = dr2;
                    best = j;
                }
            }
            if (best != null) {
                matched++;
                final double s = Math.abs(best.pt() - r.pt()) / r.pt();
                sum += s;
                max = Math.max(max, s);
            }
        }
        final double mean = matched == 0 ? 0 : sum / matched;
        final String verdict = matched < ref.size() ? "jets LOST"
            : max < 1e-9 ? "unchanged" : max < 0.02 ? "stable" : max < 0.1 ? "shifted" : "SENSITIVE";
        return new Result(test, ref.size(), matched, mean, max, verdict);
    }

    private static List<PseudoJet> hard(List<PseudoJet> event, JetDefinition def, double ptmin) {
        return PseudoJet.sortedByPt(runWithTimeout(() -> new ClusterSequence(event, def).inclusiveJets(ptmin)));
    }

    /** A clustering that does not end in 20 s is reported as hanging rather than waited for. */
    private static <T> T runWithTimeout(java.util.concurrent.Callable<T> work) {
        final ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "robustness-check");
            t.setDaemon(true);
            return t;
        });
        try {
            final Future<T> f = ex.submit(work);
            return f.get(20, TimeUnit.SECONDS);
        } catch (TimeoutException hang) {
            throw new IllegalStateException("the clustering did not end within 20 s (hangs)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            final Throwable c = e.getCause();
            throw new IllegalStateException(c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage()));
        } finally {
            ex.shutdownNow();
        }
    }

    private static String fmt(double x) {
        return String.format(Locale.ROOT, "%.3g", x);
    }
}
