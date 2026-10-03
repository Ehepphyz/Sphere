package com.sphere.core.fjcontrib.energycorrelator;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.ContribCitations;
import com.sphere.core.fjcontrib.energycorrelator.EnergyCorrelator.Measure;
import com.sphere.core.fjcontrib.energycorrelator.EnergyCorrelator.Strategy;

/**
 * The observables EnergyCorrelator builds from the ECFs and ECFGs, each
 * under its C++ name: ratios, C1, C2, D2 (A.J. Larkoski, I. Moult and
 * D. Neill, JHEP 12 (2014) 009), generalised D2, the N, M, U and C series
 * and N2, N3, M2, U1, U2, U3 (I. Moult, L. Necib and J. Thaler, JHEP 12
 * (2016) 153). {@code import static ...EnergyCorrelators.*} gives them
 * their bare names.
 */
public final class EnergyCorrelators {

    private EnergyCorrelators() {
    }

    private static double ecf(int n, double beta, Measure m, Strategy s, PseudoJet jet) {
        return new EnergyCorrelator(n, beta, m, s).result(jet);
    }

    private static double ecfg(int v, int n, double beta, Measure m, Strategy s, PseudoJet jet) {
        return new EnergyCorrelatorGeneralized(v, n, beta, m, s).result(jet);
    }

    private static String noN(double beta, Measure m, Strategy s) {
        return new EnergyCorrelator(3, beta, m, s).descriptionNoN();
    }

    /** What every observable holds: beta, measure and strategy. */
    public abstract static class Observable implements FunctionOfPseudoJet<Double> {
        protected final double beta;
        protected final Measure measure;
        protected final Strategy strategy;

        Observable(double beta, Measure measure, Strategy strategy) {
            this.beta = beta;
            this.measure = measure;
            this.strategy = strategy;
        }
    }

    /** ECF(N+1, beta)/ECF(N, beta). */
    public static final class EnergyCorrelatorRatio extends Observable {
        private final int n;

        public EnergyCorrelatorRatio(int n, double beta, Measure m, Strategy s) { super(beta, m, s); this.n = n; }
        public EnergyCorrelatorRatio(int n, double beta, Measure m) { this(n, beta, m, Strategy.storage_array); }
        public EnergyCorrelatorRatio(int n, double beta) { this(n, beta, Measure.pt_R, Strategy.storage_array); }

        @Override
        public Double result(PseudoJet jet) {
            final double num = ecf(n + 1, beta, measure, strategy, jet);
            final double den = ecf(n, beta, measure, strategy, jet);
            return num / den;
        }

        @Override
        public String description() {
            return "Energy Correlator ratio ECF(N+1,beta)/ECF(N,beta) for " + new EnergyCorrelator(n, beta, measure, strategy).descriptionParameters();
        }
    }

    /** ECF(N-1, beta) ECF(N+1, beta)/ECF(N, beta)^2. */
    public static final class EnergyCorrelatorDoubleRatio extends Observable {
        private final int n;

        public EnergyCorrelatorDoubleRatio(int n, double beta, Measure m, Strategy s) {
            super(beta, m, s);
            this.n = n;
            if (n < 1) throw new FastJetException("EnergyCorrelatorDoubleRatio:  N must be 1 or greater.");
        }

        public EnergyCorrelatorDoubleRatio(int n, double beta, Measure m) { this(n, beta, m, Strategy.storage_array); }
        public EnergyCorrelatorDoubleRatio(int n, double beta) { this(n, beta, Measure.pt_R, Strategy.storage_array); }

        @Override
        public Double result(PseudoJet jet) {
            final double num = ecf(n - 1, beta, measure, strategy, jet) * ecf(n + 1, beta, measure, strategy, jet);
            final double den = CRMath.pow(ecf(n, beta, measure, strategy, jet), 2.0);
            return num / den;
        }

        @Override
        public String description() {
            return "Energy Correlator double ratio ECF(N-1,beta)ECF(N+1,beta)/ECF(N,beta)^2 for "
                + new EnergyCorrelator(n, beta, measure, strategy).descriptionParameters();
        }
    }

    /** C1 = ECF(2)/ECF(1)^2. */
    public static final class EnergyCorrelatorC1 extends Observable {
        public EnergyCorrelatorC1(double beta, Measure m, Strategy s) { super(beta, m, s); }
        public EnergyCorrelatorC1(double beta, Measure m) { this(beta, m, Strategy.storage_array); }
        public EnergyCorrelatorC1(double beta) { this(beta, Measure.pt_R, Strategy.storage_array); }

        @Override
        public Double result(PseudoJet jet) {
            final double num = ecf(2, beta, measure, strategy, jet);
            final double den = ecf(1, beta, measure, strategy, jet);
            return num / den / den;
        }

        @Override
        public String description() {
            return "Energy Correlator observable C1 ECF(2,beta)/ECF(1,beta)^2 for " + new EnergyCorrelator(2, beta, measure, strategy).descriptionNoN();
        }
    }

    /** C2 = ECF(3) ECF(1)/ECF(2)^2. */
    public static final class EnergyCorrelatorC2 extends Observable {
        public EnergyCorrelatorC2(double beta, Measure m, Strategy s) { super(beta, m, s); }
        public EnergyCorrelatorC2(double beta, Measure m) { this(beta, m, Strategy.storage_array); }
        public EnergyCorrelatorC2(double beta) { this(beta, Measure.pt_R, Strategy.storage_array); }

        @Override
        public Double result(PseudoJet jet) {
            final double n3 = ecf(3, beta, measure, strategy, jet);
            final double n1 = ecf(1, beta, measure, strategy, jet);
            final double den = ecf(2, beta, measure, strategy, jet);
            return n3 * n1 / den / den;
        }

        @Override
        public String description() {
            return "Energy Correlator observable C2 ECF(3,beta)*ECF(1,beta)/ECF(2,beta)^2 for " + noN(beta, measure, strategy);
        }
    }

    /** D2 = ECF(3) ECF(1)^3/ECF(2)^3. */
    public static final class EnergyCorrelatorD2 extends Observable {
        public EnergyCorrelatorD2(double beta, Measure m, Strategy s) { super(beta, m, s); }
        public EnergyCorrelatorD2(double beta, Measure m) { this(beta, m, Strategy.storage_array); }
        public EnergyCorrelatorD2(double beta) { this(beta, Measure.pt_R, Strategy.storage_array); }

        @Override
        public Double result(PseudoJet jet) {
            final double n3 = ecf(3, beta, measure, strategy, jet);
            final double n1 = ecf(1, beta, measure, strategy, jet);
            final double d2 = ecf(2, beta, measure, strategy, jet);
            return n3 * n1 * n1 * n1 / d2 / d2 / d2;
        }

        @Override
        public String description() {
            return "Energy Correlator observable D2 ECF(3,beta)*ECF(1,beta)^3/ECF(2,beta)^3 for " + noN(beta, measure, strategy);
        }
    }

    /** Generalised D2 = ECFN(3, alpha)/ECFN(2, beta)^(3 alpha/beta). */
    public static final class EnergyCorrelatorGeneralizedD2 extends Observable {
        private final double alpha;

        public EnergyCorrelatorGeneralizedD2(double alpha, double beta, Measure m, Strategy s) { super(beta, m, s); this.alpha = alpha; }
        public EnergyCorrelatorGeneralizedD2(double alpha, double beta, Measure m) { this(alpha, beta, m, Strategy.storage_array); }
        public EnergyCorrelatorGeneralizedD2(double alpha, double beta) { this(alpha, beta, Measure.pt_R, Strategy.storage_array); }

        @Override
        public Double result(PseudoJet jet) {
            final double num = ecfg(-1, 3, alpha, measure, strategy, jet);
            final double den = ecfg(-1, 2, beta, measure, strategy, jet);
            return num / CRMath.pow(den, 3.0 * alpha / beta);
        }

        @Override
        public String description() {
            return "Energy Correlator observable D2 ECFN(3,alpha)/ECFN(2,beta)^(3 alpha/beta) for " + noN(beta, measure, strategy);
        }
    }

    /** N_n = ECFG(2, n+1, beta)/ECFG(1, n, beta)^2. */
    public static final class EnergyCorrelatorNseries extends Observable {
        private final int n;

        public EnergyCorrelatorNseries(int n, double beta, Measure m, Strategy s) {
            super(beta, m, s);
            this.n = n;
            if (n < 1) throw new FastJetException("EnergyCorrelatorNseries:  n must be 1 or greater.");
        }

        public EnergyCorrelatorNseries(int n, double beta, Measure m) { this(n, beta, m, Strategy.storage_array); }
        public EnergyCorrelatorNseries(int n, double beta) { this(n, beta, Measure.pt_R, Strategy.storage_array); }

        @Override
        public Double result(PseudoJet jet) {
            if (n == 1) return ecfg(1, 2, 2 * beta, measure, strategy, jet);
            final double num = ecfg(2, n + 1, beta, measure, strategy, jet);
            final double den = ecfg(1, n, beta, measure, strategy, jet);
            return num / den / den;
        }

        @Override
        public String description() {
            return "Energy Correlator observable N_n ECFG(2,n+1,beta)/ECFG(1,n,beta)^2 for " + noN(beta, measure, strategy);
        }
    }

    /** N2 = ECFG(2, 3)/ECFG(1, 2)^2. */
    public static final class EnergyCorrelatorN2 extends Observable {
        public EnergyCorrelatorN2(double beta, Measure m, Strategy s) { super(beta, m, s); }
        public EnergyCorrelatorN2(double beta, Measure m) { this(beta, m, Strategy.storage_array); }
        public EnergyCorrelatorN2(double beta) { this(beta, Measure.pt_R, Strategy.storage_array); }

        @Override
        public Double result(PseudoJet jet) {
            final double num = ecfg(2, 3, beta, measure, strategy, jet);
            final double den = ecfg(1, 2, beta, measure, strategy, jet);
            return num / den / den;
        }

        @Override
        public String description() {
            return "Energy Correlator observable N2 ECFG(2,3,beta)/ECFG(1,2,beta)^2 for " + noN(beta, measure, strategy);
        }
    }

    /** N3 = ECFG(2, 4)/ECFG(1, 3)^2. */
    public static final class EnergyCorrelatorN3 extends Observable {
        public EnergyCorrelatorN3(double beta, Measure m, Strategy s) { super(beta, m, s); }
        public EnergyCorrelatorN3(double beta, Measure m) { this(beta, m, Strategy.storage_array); }
        public EnergyCorrelatorN3(double beta) { this(beta, Measure.pt_R, Strategy.storage_array); }

        @Override
        public Double result(PseudoJet jet) {
            final double num = ecfg(2, 4, beta, measure, strategy, jet);
            final double den = ecfg(1, 3, beta, measure, strategy, jet);
            return num / den / den;
        }

        @Override
        public String description() {
            return "Energy Correlator observable N3 ECFG(2,4,beta)/ECFG(1,3,beta)^2 for " + noN(beta, measure, strategy);
        }
    }

    /** M_n = ECFG(1, n+1)/ECFG(1, n). */
    public static final class EnergyCorrelatorMseries extends Observable {
        private final int n;

        public EnergyCorrelatorMseries(int n, double beta, Measure m, Strategy s) {
            super(beta, m, s);
            this.n = n;
            if (n < 1) throw new FastJetException("EnergyCorrelatorMseries:  n must be 1 or greater.");
        }

        public EnergyCorrelatorMseries(int n, double beta, Measure m) { this(n, beta, m, Strategy.storage_array); }
        public EnergyCorrelatorMseries(int n, double beta) { this(n, beta, Measure.pt_R, Strategy.storage_array); }

        @Override
        public Double result(PseudoJet jet) {
            if (n == 1) return ecfg(1, 2, beta, measure, strategy, jet);
            final double num = ecfg(1, n + 1, beta, measure, strategy, jet);
            final double den = ecfg(1, n, beta, measure, strategy, jet);
            return num / den;
        }

        @Override
        public String description() {
            return "Energy Correlator observable M_n ECFG(1,n+1,beta)/ECFG(1,n,beta) for " + noN(beta, measure, strategy);
        }
    }

    /** M2 = ECFG(1, 3)/ECFG(1, 2). */
    public static final class EnergyCorrelatorM2 extends Observable {
        public EnergyCorrelatorM2(double beta, Measure m, Strategy s) { super(beta, m, s); }
        public EnergyCorrelatorM2(double beta, Measure m) { this(beta, m, Strategy.storage_array); }
        public EnergyCorrelatorM2(double beta) { this(beta, Measure.pt_R, Strategy.storage_array); }

        @Override
        public Double result(PseudoJet jet) {
            final double num = ecfg(1, 3, beta, measure, strategy, jet);
            final double den = ecfg(1, 2, beta, measure, strategy, jet);
            return num / den;
        }

        @Override
        public String description() {
            return "Energy Correlator observable M2 ECFG(1,3,beta)/ECFG(1,2,beta) for " + noN(beta, measure, strategy);
        }
    }

    /** C_N with normalised ECFs: ECFN(N-1) ECFN(N+1)/ECFN(N)^2. */
    public static final class EnergyCorrelatorCseries extends Observable {
        private final int n;

        public EnergyCorrelatorCseries(int n, double beta, Measure m, Strategy s) {
            super(beta, m, s);
            this.n = n;
            if (n < 1) throw new FastJetException("EnergyCorrelatorCseries:  N must be 1 or greater.");
        }

        public EnergyCorrelatorCseries(int n, double beta, Measure m) { this(n, beta, m, Strategy.storage_array); }
        public EnergyCorrelatorCseries(int n, double beta) { this(n, beta, Measure.pt_R, Strategy.storage_array); }

        @Override
        public Double result(PseudoJet jet) {
            final double num = ecfg(-1, n - 1, beta, measure, strategy, jet) * ecfg(-1, n + 1, beta, measure, strategy, jet);
            final double den = CRMath.pow(ecfg(-1, n, beta, measure, strategy, jet), 2.0);
            return num / den;
        }

        @Override
        public String description() {
            return "Energy Correlator double ratio ECFN(N-1,beta)ECFN(N+1,beta)/ECFN(N,beta)^2 for "
                + new EnergyCorrelator(n, beta, measure, strategy).descriptionParameters();
        }
    }

    /** U_n = ECFG(1, n+1). */
    public static final class EnergyCorrelatorUseries extends Observable {
        private final int n;

        public EnergyCorrelatorUseries(int n, double beta, Measure m, Strategy s) {
            super(beta, m, s);
            this.n = n;
            if (n < 1) throw new FastJetException("EnergyCorrelatorUseries:  n must be 1 or greater.");
        }

        public EnergyCorrelatorUseries(int n, double beta, Measure m) { this(n, beta, m, Strategy.storage_array); }
        public EnergyCorrelatorUseries(int n, double beta) { this(n, beta, Measure.pt_R, Strategy.storage_array); }

        @Override
        public Double result(PseudoJet jet) {
            return ecfg(1, n + 1, beta, measure, strategy, jet);
        }

        @Override
        public String description() {
            return "Energy Correlator observable U_n ECFG(1,n+1,beta) for " + noN(beta, measure, strategy);
        }
    }

    /** U1 = ECFG(1, 2). */
    public static final class EnergyCorrelatorU1 extends Observable {
        public EnergyCorrelatorU1(double beta, Measure m, Strategy s) { super(beta, m, s); }
        public EnergyCorrelatorU1(double beta, Measure m) { this(beta, m, Strategy.storage_array); }
        public EnergyCorrelatorU1(double beta) { this(beta, Measure.pt_R, Strategy.storage_array); }

        @Override
        public Double result(PseudoJet jet) {
            return ecfg(1, 2, beta, measure, strategy, jet);
        }

        @Override
        public String description() {
            return "Energy Correlator observable U_1 ECFG(1,2,beta) for " + noN(beta, measure, strategy);
        }
    }

    /** U2 = ECFG(1, 3). */
    public static final class EnergyCorrelatorU2 extends Observable {
        public EnergyCorrelatorU2(double beta, Measure m, Strategy s) { super(beta, m, s); }
        public EnergyCorrelatorU2(double beta, Measure m) { this(beta, m, Strategy.storage_array); }
        public EnergyCorrelatorU2(double beta) { this(beta, Measure.pt_R, Strategy.storage_array); }

        @Override
        public Double result(PseudoJet jet) {
            return ecfg(1, 3, beta, measure, strategy, jet);
        }

        @Override
        public String description() {
            return "Energy Correlator observable U_2 ECFG(1,3,beta) for " + noN(beta, measure, strategy);
        }
    }

    /** U3 = ECFG(1, 4). */
    public static final class EnergyCorrelatorU3 extends Observable {
        public EnergyCorrelatorU3(double beta, Measure m, Strategy s) { super(beta, m, s); }
        public EnergyCorrelatorU3(double beta, Measure m) { this(beta, m, Strategy.storage_array); }
        public EnergyCorrelatorU3(double beta) { this(beta, Measure.pt_R, Strategy.storage_array); }

        @Override
        public Double result(PseudoJet jet) {
            return ecfg(1, 4, beta, measure, strategy, jet);
        }

        @Override
        public String description() {
            return "Energy Correlator observable U_3 ECFG(1,4,beta) for " + noN(beta, measure, strategy);
        }
    }

    static {
        ContribCitations.use("ecf");
    }
}
