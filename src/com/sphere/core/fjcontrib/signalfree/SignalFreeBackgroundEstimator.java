package com.sphere.core.fjcontrib.signalfree;

import com.sphere.core.fastjet.AreaDefinition;
import com.sphere.core.fastjet.ClusterSequenceArea;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.GhostedAreaSpec;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.LimitedWarning;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.RectangularGrid;
import com.sphere.core.fastjet.tools.BackgroundEstimate;
import com.sphere.core.fastjet.tools.BackgroundEstimatorBase;
import com.sphere.core.fastjet.tools.GridMedianBackgroundEstimator;
import com.sphere.core.fjcontrib.ContribCitations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A pileup density that does not grow with the number of signal jets,
 * fastjet::contrib::SignalFreeBackgroundEstimator
 * (SignalFreeBackgroundEstimator 1.0.1; P. Berta, J. Smiesko and M. Spousta,
 * "Pileup density estimate independent on jet multiplicity",
 * arXiv:2304.08383).
 *
 * <p>The grid of GridMedianBackgroundEstimator, but the areas around signal
 * seeds (anti-kt jets of the estimated signal, or of charged signal tracks,
 * above a density, or seeds given directly) are cut out of each tile, and rho
 * is a weighted median over what remains, taken in a window that may float
 * with the fraction of signal in the event.
 *
 * <p>Where the C++ prints its warnings to cout, they go to FastJet's warning
 * sink (the console) here; {@link #lastExcludedFraction()} and
 * {@link #lastSeeds()} say what the last event excluded, Sphere's additions.
 */
public class SignalFreeBackgroundEstimator extends BackgroundEstimatorBase {

    static {
        ContribCitations.use("signalfree");
    }

    private final RectangularGrid grid;
    private final double rapidityMax;
    private double signalJetsR = 0.3;
    private double exclusionDeltaR = 0.4;
    private double jetRhoMinIntercept = 5;
    private double jetRhoMinSlope = 8;
    private double jetRhoMinCharged = 20;
    private double tileAreaMin = 0.00001;
    private double ghostSize = 0.04;
    private double center = 0.5;
    private double halfWindow = 0.1;
    private double regulatorForFloatingCenter = -1;
    private double signalFractionMin = 0.005;
    private double shiftMax = 0.15;
    private double signalFraction;
    private double weightsSum;
    private boolean useWeightedTiles = true;
    private boolean enableRhoM = true;
    private List<PseudoJet> seedsFromUser = new ArrayList<>();
    private final LimitedWarning warningRescaling = new LimitedWarning();
    private static final LimitedWarning NEGATIVE_TILE = new LimitedWarning();
    private static final LimitedWarning ALL_EXCLUDED = new LimitedWarning();

    private List<PseudoJet> lastSeeds = List.of();
    private double lastExcludedFraction = Double.NaN;

    public SignalFreeBackgroundEstimator(double rapidityMax, double requestedGridSpacing) {
        this.grid = new RectangularGrid(rapidityMax, requestedGridSpacing);
        this.rapidityMax = rapidityMax;
    }

    public RectangularGrid grid() {
        return grid;
    }

    /* ------------------------------------------------------------------ */
    /* Parameters                                                          */
    /* ------------------------------------------------------------------ */

    /** R of the anti-kt signal jets, and the exclusion radius around the seeds. */
    public void setSignalSeedParameters(double signalJetsR, double exclusionDeltaR) {
        this.signalJetsR = signalJetsR;
        this.exclusionDeltaR = exclusionDeltaR;
    }

    /** The density a signal jet must exceed: intercept + slope sqrt(pileup measure). */
    public void setJetRhoMin(double intercept, double slope) {
        jetRhoMinIntercept = intercept;
        jetRhoMinSlope = slope;
    }

    public void setJetRhoMinCharged(double v) { jetRhoMinCharged = v; }
    public void setGhostSize(double v) { ghostSize = v; }
    public void setTileAreaMin(double v) { tileAreaMin = v; }
    public void setUseWeightedTiles(boolean v) { useWeightedTiles = v; }
    public void setComputeRhoM(boolean v) { enableRhoM = v; }

    public void setWindowParameters(double center, double halfWindow) {
        setWindowParameters(center, halfWindow, -1, 0.005, 0.15);
    }

    public void setWindowParameters(double center, double halfWindow, double regulatorForFloatingCenter) {
        setWindowParameters(center, halfWindow, regulatorForFloatingCenter, 0.005, 0.15);
    }

    public void setWindowParameters(double center, double halfWindow, double regulatorForFloatingCenter,
                                    double signalFractionMin, double shiftMax) {
        this.center = center;
        this.halfWindow = halfWindow;
        this.regulatorForFloatingCenter = regulatorForFloatingCenter;
        this.signalFractionMin = signalFractionMin;
        this.shiftMax = shiftMax;
    }

    /** Seeds of one's own, used with the others; to be given before setParticles. */
    public void addSeedsFromUser(List<PseudoJet> seeds) {
        seedsFromUser = new ArrayList<>(seeds);
    }

    /* ------------------------------------------------------------------ */
    /* The event                                                           */
    /* ------------------------------------------------------------------ */

    @Override
    public void setParticles(List<PseudoJet> particles) {
        setParticles(particles, List.of(), -1, List.of());
    }

    public void setParticles(List<PseudoJet> particles, List<PseudoJet> signalParticles) {
        setParticles(particles, signalParticles, -1, List.of());
    }

    /**
     * @param signalParticles        the estimated signal (e.g. an event already corrected)
     * @param measureOfPileup        nPU, nPV, mu or rho; -1 for a constant density threshold
     * @param signalChargedParticles charged signal particles, clustered separately
     */
    public void setParticles(List<PseudoJet> particles, List<PseudoJet> signalParticles, double measureOfPileup,
                             List<PseudoJet> signalChargedParticles) {
        if (!grid.allTilesEqualArea()) throw new FastJetException("SignalFreeBackgroundEstimator: tiles of unequal area");
        cacheAvailable = false;
        cachedEstimate = new BackgroundEstimate();
        cachedEstimate.setHasSigma(false);
        cachedEstimate.setMeanArea(grid.meanTileArea());

        final AreaDefinition areaDef = new AreaDefinition(AreaDefinition.AreaType.ACTIVE, new GhostedAreaSpec(rapidityMax));
        final JetDefinition jetDef = new JetDefinition(JetAlgorithm.ANTIKT, signalJetsR);
        final List<PseudoJet> allSignalJets = PseudoJet.sortedByPt(
            new ClusterSequenceArea(signalParticles, jetDef, areaDef).inclusiveJets());
        final List<PseudoJet> seeds = new ArrayList<>();
        double jetRhoMin = jetRhoMinIntercept;
        if (measureOfPileup > 0) jetRhoMin = jetRhoMinIntercept + Math.sqrt(measureOfPileup) * jetRhoMinSlope;
        for (PseudoJet j : allSignalJets) {
            if (j.pt() / j.area() > jetRhoMin) seeds.add(j);
        }
        if (!signalChargedParticles.isEmpty()) {
            final List<PseudoJet> chargedJets = PseudoJet.sortedByPt(
                new ClusterSequenceArea(signalChargedParticles, jetDef, areaDef).inclusiveJets());
            for (PseudoJet j : chargedJets) {
                if (j.pt() / j.area() > jetRhoMinCharged) seeds.add(j);
            }
        }
        seeds.addAll(seedsFromUser);
        lastSeeds = List.copyOf(seeds);

        double scalarPtSumSignal = 0;
        for (PseudoJet p : signalParticles) scalarPtSumSignal += p.pt();
        signalFraction = scalarPtSumSignal / 14000.;

        // the area of each tile left once the signal is cut out, measured with ghosts
        final int nTiles = grid.nTiles();
        final boolean[] tileStates = new boolean[nTiles];
        final double[] tileAreas = new double[nTiles];
        final int nGhostsPhi = (int) (grid.dphi() / ghostSize);
        final int nGhostsRap = (int) (grid.drap() / ghostSize);
        final PseudoJet ghost = new PseudoJet(0, 0, 0, 1);
        final int nPhi = (int) Math.round(2 * Math.PI / grid.dphi());
        double excluded = 0;
        for (int i = 0; i < nTiles; i++) {
            double areaFraction = 1;
            if (useWeightedTiles) {
                final int tileIndexPhi = i % nPhi;
                final int tileIndexRap = i / nPhi;
                int close = 0;
                for (int iphi = 0; iphi < nGhostsPhi; iphi++) {
                    final double phi = (tileIndexPhi + (iphi + 0.5) / (double) nGhostsPhi) * grid.dphi();
                    for (int irap = 0; irap < nGhostsRap; irap++) {
                        final double rap = (tileIndexRap + (irap + 0.5) / (double) nGhostsRap) * grid.drap() + grid.rapmin();
                        ghost.resetMomentumPtYPhiM(1, rap, phi, 1e-200);
                        for (PseudoJet s : seeds) {
                            if (s.deltaR(ghost) < exclusionDeltaR) {
                                close++;
                                break;
                            }
                        }
                    }
                }
                areaFraction = 1 - close / (double) (nGhostsPhi * nGhostsRap);
            }
            tileAreas[i] = grid.meanTileArea() * areaFraction;
            excluded += 1 - areaFraction;
            if (tileAreas[i] > tileAreaMin) tileStates[i] = true;
        }
        lastExcludedFraction = excluded / nTiles;

        final double[] tilePt = new double[nTiles];
        final double[] tileMtMinusPt = new double[nTiles];
        for (PseudoJet p : particles) {
            final int tileIndex = grid.tileIndex(p);
            if (tileIndex < 0) {
                NEGATIVE_TILE.warn("SignalFreeBackgroundEstimator::set_particles: Tile index is negative. Particle (pt,rap,phi,m): "
                    + p.pt() + " " + p.rap() + " " + p.phi() + " " + p.m());
                continue;
            }
            boolean close = false;
            for (PseudoJet s : seeds) {
                if (s.deltaR(p) < exclusionDeltaR) {
                    close = true;
                    break;
                }
            }
            if (close) continue;
            if (rescalingClass != null) {
                final double r = rescalingClass.result(p);
                tilePt[tileIndex] += p.pt() / r;
                if (enableRhoM) tileMtMinusPt[tileIndex] += (p.mt() - p.pt()) / r;
            } else {
                tilePt[tileIndex] += p.pt();
                if (enableRhoM) tileMtMinusPt[tileIndex] += p.mt() - p.pt();
            }
        }

        final List<double[]> tileRho = new ArrayList<>();
        final List<double[]> tileRhom = new ArrayList<>();
        weightsSum = 0;
        for (int i = 0; i < nTiles; i++) {
            if (!tileStates[i]) continue;
            tileRho.add(new double[]{tilePt[i] / tileAreas[i], tileAreas[i]});
            weightsSum += tileAreas[i];
            if (enableRhoM) tileRhom.add(new double[]{tileMtMinusPt[i] / tileAreas[i], tileAreas[i]});
        }
        if (tileRho.isEmpty()) {
            ALL_EXCLUDED.warn("SignalFreeBackgroundEstimator::set_particles: All tiles are excluded in this event, and it "
                + "is not possible to estimate rho with SignalFreeBackgroundEstimator! Estimating it with "
                + "GridMedianBackgroundEstimator instead.");
            final GridMedianBackgroundEstimator gridMedian = new GridMedianBackgroundEstimator(rapidityMax, 0.55);
            if (rescalingClass != null) gridMedian.setRescalingClass(rescalingClass);
            gridMedian.setParticles(particles);
            cachedEstimate = gridMedian.estimate();
            cacheAvailable = true;
            return;
        }
        cachedEstimate.setRho(weightedMedian(tileRho));
        if (enableRhoM) {
            cachedEstimate.setHasRhoM(true);
            cachedEstimate.setRhoM(weightedMedian(tileRhom));
        }
        cacheAvailable = true;
    }

    /** The mean of the tile densities inside the window of cumulative area, the tiles sorted by density. */
    private double weightedMedian(List<double[]> tiles) {
        final double[][] t = tiles.toArray(new double[0][]);
        Arrays.sort(t, (x, y) -> x[0] != y[0] ? Double.compare(x[0], y[0]) : Double.compare(x[1], y[1]));
        double shift = 0;
        if (regulatorForFloatingCenter >= 0) shift = (signalFraction - signalFractionMin) * regulatorForFloatingCenter;
        if (shift < 0) shift = 0;
        if (shift > shiftMax) shift = shiftMax;
        if (shift > center - halfWindow) shift = center - halfWindow;
        final double centerShifted = center - shift;

        double num = 0;
        double den = 0;
        boolean inside = false;
        double partial = 0;
        for (double[] tile : t) {
            final double fraction = tile[1] / weightsSum;
            final double toStart = centerShifted - halfWindow - partial;
            final double toStop = centerShifted + halfWindow - partial;
            if (toStart < fraction && !inside) {
                num = tile[0] * (fraction - toStart);
                den = fraction - toStart;
                inside = true;
                if (toStop < fraction) break;
            } else if (toStop < fraction) {
                num += tile[0] * toStop;
                den += toStop;
                break;
            } else if (inside) {
                num += tile[0] * fraction;
                den += fraction;
            }
            partial += fraction;
        }
        return num / den;
    }

    private void verifyParticlesSet() {
        if (!cacheAvailable) {
            throw new FastJetException("SignalFreeBackgroundEstimator::verify_particles_set: rho() or sigma() called without particles having been set");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Answers                                                             */
    /* ------------------------------------------------------------------ */

    @Override
    public BackgroundEstimate estimate() {
        verifyParticlesSet();
        return new BackgroundEstimate(cachedEstimate);
    }

    @Override
    public BackgroundEstimate estimate(PseudoJet jet) {
        verifyParticlesSet();
        final BackgroundEstimate local = new BackgroundEstimate(cachedEstimate);
        if (rescalingClass != null) local.applyRescalingFactor(rescalingClass.result(jet));
        return local;
    }

    @Override
    public double rho() {
        verifyParticlesSet();
        return cachedEstimate.rho();
    }

    @Override
    public double rho(PseudoJet jet) {
        return rescalingClass != null ? rescalingClass.result(jet) * cachedEstimate.rho() : cachedEstimate.rho();
    }

    @Override
    public boolean hasRhoM() {
        return enableRhoM;
    }

    @Override
    public double rhoM() {
        if (!enableRhoM) {
            throw new FastJetException("SignalFreeBackgroundEstimator: rho_m requested but rho_m calculation has been disabled.");
        }
        verifyParticlesSet();
        return cachedEstimate.rhoM();
    }

    @Override
    public double rhoM(PseudoJet jet) {
        final double rescaling = rescalingClass == null ? 1.0 : rescalingClass.result(jet);
        return rescaling * rhoM();
    }

    @Override
    public void setRescalingClass(FunctionOfPseudoJet<Double> r) {
        if (!cacheAvailable) {
            warningRescaling.warn("SignalFreeBackgroundEstimator::set_rescaling_class: Found cached result. Set particles again to obtain correct calculation!");
        }
        super.setRescalingClass(r);
    }

    @Override
    public String description() {
        return "SignalFreeBackgroundEstimator, with " + grid.description();
    }

    /** The seeds the last event was cut around. */
    public List<PseudoJet> lastSeeds() {
        return lastSeeds;
    }

    /** The fraction of the grid's area the last event cut out. */
    public double lastExcludedFraction() {
        return lastExcludedFraction;
    }
}
