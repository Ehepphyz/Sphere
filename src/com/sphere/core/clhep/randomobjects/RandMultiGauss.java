package com.sphere.core.clhep.randomobjects;

import com.sphere.core.clhep.CMath;
import com.sphere.core.clhep.matrix.HepMatrix;
import com.sphere.core.clhep.matrix.HepSymMatrix;
import com.sphere.core.clhep.matrix.HepVector;
import com.sphere.core.clhep.matrix.MatrixLinear;
import com.sphere.core.clhep.random.HepRandomEngine;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

/**
 * CLHEP's RandMultiGauss (RandomObjects/RandMultiGauss.h, .cc): vectors of the multivariate Gaussian of mean
 * mu and covariance S. S is diagonalized once (S = U D U^T), the deviates drawn by the polar method, each
 * pair's second kept for the next vector, scaled by sqrt(D) and rotated by U. Where CLHEP prints and exits
 * (dimensions that differ, S not positive definite) this prints the same and throws.
 *
 * <p>As in CLHEP, the constructor from an engine alone leaves a 2x1 U, so fire() then fails the product.
 */
public class RandMultiGauss extends HepRandomVector {

    private final HepRandomEngine localEngine;
    private HepVector defaultMu;
    private HepMatrix defaultU;
    private HepVector defaultSigmas;
    private final boolean[] set = {false};
    private final double[] nextGaussian = {0.0};

    public RandMultiGauss(HepRandomEngine anEngine, HepVector mu, HepSymMatrix S) {
        super(anEngine);
        localEngine = anEngine;
        if (S.num_row() != mu.num_row()) {
            exit(new COStream().put("In constructor of RandMultiGauss distribution: \n").put("      Dimension of mu (")
                .put(mu.num_row()).put(") does not match dimension of S (").put(S.num_row()).put(")\n").str());
        }
        defaultMu = new HepVector(mu);
        defaultSigmas = new HepVector(S.num_row());
        defaultU = prepareUsigmas(S, defaultSigmas);
    }

    public RandMultiGauss(HepRandomEngine anEngine) {
        super(anEngine);
        localEngine = anEngine;
        defaultMu = new HepVector(2, 0);
        defaultU = new HepMatrix(2, 1);
        defaultSigmas = new HepVector(2);
        defaultSigmas.set(1, 1.);
        defaultSigmas.set(2, 1.);
    }

    /** CLHEP's message, "---Exiting to System", then the exit as an exception. */
    private static void exit(String message) {
        StdStreams.cerr().print(message);
        StdStreams.cerr().print("---Exiting to System\n");
        throw new IllegalStateException(message.replace("\n", " ").replaceAll(" +", " ").trim());
    }

    /** U diagonalizing S, sigmas the square roots of its eigenvalues (S positive definite, or an exit). */
    private static HepMatrix prepareUsigmas(HepSymMatrix S, HepVector sigmas) {
        final HepSymMatrix tempS = new HepSymMatrix(S);
        final HepMatrix U = MatrixLinear.diagonalize(tempS);
        final HepSymMatrix D = S.similarityT(U);
        for (int i = 1; i <= S.num_row(); i++) {
            final double s2 = D.get(i, i);
            if (s2 > 0) {
                sigmas.set(i, Math.sqrt(s2));
            } else {
                final COStream o = new COStream().put("In RandMultiGauss distribution: \n")
                    .put("      Matrix S is not positive definite.  Eigenvalues are:\n");
                for (int ixx = 1; ixx <= S.num_row(); ixx++) o.put("      ").put(D.get(ixx, ixx)).put("\n");
                exit(o.str());
            }
        }
        return U;
    }

    private static HepVector deviates(HepMatrix U, HepVector sigmas, HepRandomEngine engine, boolean[] available,
                                      double[] next) {
        final int n = sigmas.num_row();
        final HepVector v = new HepVector(n);
        double r;
        double v1;
        double v2;
        double fac;
        int i = 1;
        if (available[0]) {
            v.set(1, next[0]);
            i = 2;
            available[0] = false;
        }
        while (i <= n) {
            do {
                v1 = 2.0 * engine.flat() - 1.0;
                v2 = 2.0 * engine.flat() - 1.0;
                r = v1 * v1 + v2 * v2;
            } while (r > 1.0);
            fac = Math.sqrt(-2.0 * CMath.log(r) / r);
            v.set(i++, v1 * fac);
            if (i <= n) {
                v.set(i++, v2 * fac);
            } else {
                next[0] = v2 * fac;
                available[0] = true;
            }
        }
        for (i = 1; i <= n; i++) v.set(i, v.get(i) * sigmas.get(i));
        return U.times(v);
    }

    /** A vector of the default mu and S; operator(). */
    @Override
    public HepVector fire() {
        return defaultMu.plus(deviates(defaultU, defaultSigmas, localEngine, set, nextGaussian));
    }

    public HepVector fire(HepVector mu, HepSymMatrix S) {
        final HepVector sigmas = new HepVector(mu.num_row());
        if (mu.num_row() == S.num_row()) {
            final HepMatrix U = prepareUsigmas(S, sigmas);
            return mu.plus(deviates(U, sigmas, localEngine, set, nextGaussian));
        }
        exit(new COStream().put("In firing RandMultiGauss distribution with explicit mu and S: \n")
            .put("      Dimension of mu (").put(mu.num_row()).put(") does not match dimension of S (").put(S.num_row())
            .put(")\n").str());
        return mu;
    }

    public void fireArray(int size, HepVector[] array) {
        for (int i = 0; i < size; ++i) {
            array[i] = defaultMu.plus(deviates(defaultU, defaultSigmas, localEngine, set, nextGaussian));
        }
    }

    public void fireArray(int size, HepVector[] array, HepVector mu, HepSymMatrix S) {
        final HepVector sigmas = new HepVector(mu.num_row());
        final HepVector mu_ = new HepVector(mu);
        HepMatrix U = new HepMatrix();
        if (mu.num_row() == S.num_row()) {
            U = prepareUsigmas(S, sigmas);
        } else {
            exit(new COStream().put("In fireArray for RandMultiGauss distribution with explicit mu and S: \n")
                .put("      Dimension of mu (").put(mu.num_row()).put(") does not match dimension of S (").put(S.num_row())
                .put(")\n").str());
        }
        for (int i = 0; i < size; ++i) array[i] = mu_.plus(deviates(U, sigmas, localEngine, set, nextGaussian));
    }
}
