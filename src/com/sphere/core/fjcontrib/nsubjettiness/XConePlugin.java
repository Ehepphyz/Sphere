package com.sphere.core.fjcontrib.nsubjettiness;

import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fjcontrib.ContribCitations;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.GenET_GenKT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition.OnePass_GenET_GenKT_Axes;
import com.sphere.core.fjcontrib.nsubjettiness.MeasureDefinition.XConeMeasure;

/**
 * The XCone jet algorithm, XConePlugin (I.W. Stewart, F.J. Tackmann,
 * J. Thaler, C.K. Vermilion and T.F. Wilkason, JHEP 11 (2015) 072): exactly
 * N jets of radius about R0, the N-jettiness plugin with one-pass
 * generalised-kt axes matched to beta and the XCone measure. beta = 2 gives
 * "mean" jets, beta = 1 recoil-free "median" ones.
 */
public class XConePlugin extends NjettinessPlugin {

    static {
        ContribCitations.use("xcone");
    }

    private final int n;
    private final double r0;
    private final double beta;

    public XConePlugin(int n, double r0, double beta) {
        super(n, new OnePass_GenET_GenKT_Axes(calcDelta(beta), calcPower(beta), r0), new XConeMeasure(beta, r0));
        this.n = n;
        this.r0 = r0;
        this.beta = beta;
    }

    public XConePlugin(int n, double r0) {
        this(n, r0, 2.0);
    }

    static double calcDelta(double beta) {
        return beta > 1 ? 1 / (beta - 1) : Integer.MAX_VALUE;
    }

    static double calcPower(double beta) {
        return 1.0 / beta;
    }

    @Override
    public String description() {
        return "XCone Jet Algorithm with N = " + Fmt.g(n) + ", Rcut = " + Fmt.f(r0, 0, 2) + ", beta = " + Fmt.f(beta, 0, 2);
    }

    @Override
    public double R() {
        return r0;
    }

    /** XCone without the minimisation, for tests, PseudoXConePlugin. */
    public static class PseudoXConePlugin extends NjettinessPlugin {
        private final int n;
        private final double r0;
        private final double beta;

        public PseudoXConePlugin(int n, double r0, double beta) {
            super(n, new GenET_GenKT_Axes(calcDelta(beta), calcPower(beta), r0), new XConeMeasure(beta, r0));
            this.n = n;
            this.r0 = r0;
            this.beta = beta;
        }

        public PseudoXConePlugin(int n, double r0) {
            this(n, r0, 2.0);
        }

        @Override
        public String description() {
            return "PseudoXCone Jet Algorithm with N = " + Fmt.g(n) + ", Rcut = " + Fmt.f(r0, 0, 2) + ", beta = "
                + Fmt.f(beta, 0, 2);
        }

        @Override
        public double R() {
            return r0;
        }
    }
}
