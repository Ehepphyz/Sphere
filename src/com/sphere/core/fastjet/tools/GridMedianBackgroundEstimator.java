package com.sphere.core.fastjet.tools;

import com.sphere.core.fastjet.Citations;

import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.LimitedWarning;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.RectangularGrid;
import com.sphere.core.fastjet.Selector;

import java.util.Arrays;
import java.util.List;

/**
 * rho as the median of the scalar pt in the cells of a grid, divided by the
 * cell area, fastjet::GridMedianBackgroundEstimator. No clustering is
 * needed, which makes it the fast estimator.
 */
public class GridMedianBackgroundEstimator extends BackgroundEstimatorBase {

    static {
        Citations.use("rho"); // listed in the console's Citations menu once used
    }

    private final RectangularGrid grid;
    private boolean enableRhoM = true;
    private final LimitedWarning warningRescaling = new LimitedWarning();

    public GridMedianBackgroundEstimator(double ymax, double requestedGridSpacing) {
        grid = new RectangularGrid(ymax, requestedGridSpacing);
    }

    public GridMedianBackgroundEstimator(RectangularGrid grid) {
        if (!grid.isInitialised()) {
            throw new FastJetException("attempt to construct GridMedianBackgroundEstimator with uninitialised RectangularGrid");
        }
        this.grid = new RectangularGrid(grid);
    }

    public GridMedianBackgroundEstimator(double rapmin, double rapmax, double drap, double dphi, Selector tileSelector) {
        grid = new RectangularGrid(rapmin, rapmax, drap, dphi, tileSelector);
    }

    public RectangularGrid grid() {
        return grid;
    }

    public void setComputeRhoM(boolean enable) {
        enableRhoM = enable;
    }

    @Override
    public void setParticles(List<PseudoJet> particles) {
        final int n = grid.nTiles();
        final double[] scalarPt = new double[n];
        cachedEstimate = new BackgroundEstimate();
        cachedEstimate.setHasSigma(true);
        cachedEstimate.setMeanArea(grid.meanTileArea());
        if (enableRhoM) {
            final double[] scalarDt = new double[n];
            for (PseudoJet p : particles) {
                final int j = grid.tileIndex(p);
                if (j >= 0) {
                    final double pt = p.pt();
                    final double dt = p.mt() - pt;
                    if (rescalingClass == null) {
                        scalarPt[j] += pt;
                        scalarDt[j] += dt;
                    } else {
                        final double r = rescalingClass.result(p);
                        scalarPt[j] += pt / r;
                        scalarDt[j] += dt / r;
                    }
                }
            }
            Arrays.sort(scalarDt);
            final double p50 = percentile(scalarDt, 0.5, 0.0, false);
            cachedEstimate.setHasRhoM(true);
            cachedEstimate.setRhoM(p50 / grid.meanTileArea());
            cachedEstimate.setSigmaM((p50 - percentile(scalarDt, (1.0 - 0.6827) / 2.0, 0.0, false))
                / Math.sqrt(grid.meanTileArea()));
        } else {
            for (PseudoJet p : particles) {
                final int j = grid.tileIndex(p);
                if (j >= 0) {
                    scalarPt[j] += rescalingClass == null ? p.pt() : p.pt() / rescalingClass.result(p);
                }
            }
        }
        double[] good = scalarPt;
        if (grid.nGoodTiles() != grid.nTiles()) {
            int newn = 0;
            for (int i = 0; i < scalarPt.length; i++) {
                if (grid.tileIsGood(i)) {
                    final double t = scalarPt[i];
                    scalarPt[i] = scalarPt[newn];
                    scalarPt[newn] = t;
                    newn++;
                }
            }
            good = Arrays.copyOf(scalarPt, newn);
        }
        Arrays.sort(good);
        final double p50 = percentile(good, 0.5, 0.0, false);
        cachedEstimate.setRho(p50 / grid.meanTileArea());
        cachedEstimate.setSigma((p50 - percentile(good, (1.0 - 0.6827) / 2.0, 0.0, false))
            / Math.sqrt(grid.meanTileArea()));
        cacheAvailable = true;
    }

    private void verifyParticlesSet() {
        if (!cacheAvailable) {
            throw new FastJetException("GridMedianBackgroundEstimator::rho() or sigma() called without particles having been set");
        }
    }

    @Override
    public BackgroundEstimate estimate() {
        verifyParticlesSet();
        return new BackgroundEstimate(cachedEstimate);
    }

    @Override
    public BackgroundEstimate estimate(PseudoJet jet) {
        verifyParticlesSet();
        final BackgroundEstimate local = new BackgroundEstimate(cachedEstimate);
        if (rescalingClass != null) {
            local.applyRescalingFactor(rescalingClass.result(jet));
        }
        return local;
    }

    @Override
    public double rho() {
        verifyParticlesSet();
        return cachedEstimate.rho();
    }

    @Override
    public double sigma() {
        verifyParticlesSet();
        return cachedEstimate.sigma();
    }

    @Override
    public double rho(PseudoJet jet) {
        return (rescalingClass == null ? 1.0 : rescalingClass.result(jet)) * rho();
    }

    @Override
    public double sigma(PseudoJet jet) {
        return (rescalingClass == null ? 1.0 : rescalingClass.result(jet)) * sigma();
    }

    @Override
    public boolean hasSigma() {
        return true;
    }

    @Override
    public double rhoM() {
        if (!enableRhoM) {
            throw new FastJetException("GridMediamBackgroundEstimator: rho_m requested but rho_m calculation has been disabled.");
        }
        verifyParticlesSet();
        return cachedEstimate.rhoM();
    }

    @Override
    public double sigmaM() {
        if (!enableRhoM) {
            throw new FastJetException("GridMediamBackgroundEstimator: sigma_m requested but rho_m/sigma_m calculation has been disabled.");
        }
        verifyParticlesSet();
        return cachedEstimate.sigmaM();
    }

    @Override
    public double rhoM(PseudoJet jet) {
        return (rescalingClass == null ? 1.0 : rescalingClass.result(jet)) * rhoM();
    }

    @Override
    public double sigmaM(PseudoJet jet) {
        return (rescalingClass == null ? 1.0 : rescalingClass.result(jet)) * sigmaM();
    }

    @Override
    public boolean hasRhoM() {
        return enableRhoM;
    }

    public double meanArea() {
        return grid.meanTileArea();
    }

    @Override
    public void setRescalingClass(FunctionOfPseudoJet<Double> r) {
        if (cacheAvailable) {
            warningRescaling.warn("GridMedianBackgroundEstimator::set_rescaling_class(): trying to set the rescaling class when there are already particles that have been set is dangerous: the rescaling will not affect the already existing particles resulting in mis-estimation of rho. You need to call set_particles() again before proceeding with any background estimation.");
        }
        super.setRescalingClass(r);
    }

    @Override
    public String description() {
        return "GridMedianBackgroundEstimator, with " + grid.description();
    }
}
