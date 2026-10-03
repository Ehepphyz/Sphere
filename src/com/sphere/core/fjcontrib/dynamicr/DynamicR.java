package com.sphere.core.fjcontrib.dynamicr;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.NNBriefJet;
import com.sphere.core.fastjet.NNH;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.RecombinationScheme;
import com.sphere.core.fjcontrib.ContribCitations;

import java.util.List;
import java.util.function.Function;

/**
 * The Dynamic Radius jet algorithm, fastjet::contrib::DynamicR (DynamicR
 * 1.0.2; B. Mukhopadhyaya, T. Samui and R.K. Singh, "Dynamic Radius Jet
 * Clustering Algorithm", JHEP 04 (2023) 019, arXiv:2301.13074).
 *
 * <p>kt-, C/A- or anti-kt-like pairwise distances, but a beam distance whose
 * radius grows as the jet does: R_d = R0 + sigma, sigma the pt-weighted
 * spread of the pairwise distances between its constituents, carried along
 * the clustering in a {@link JetInfo}. A two-prong jet whose prongs are far
 * apart thus keeps growing, a narrow jet stops at R0.
 */
public class DynamicR implements JetDefinition.Plugin {

    static {
        ContribCitations.use("dynamicr");
    }

    /** The pairwise distance family. */
    public enum Algorithm {
        /** kt-like: min(pt_i^2, pt_j^2) dR^2. */
        DRKT,
        /** Cambridge/Aachen-like: dR^2. */
        DRCA,
        /** anti-kt-like: dR^2 / max(pt_i^2, pt_j^2). */
        DRAK
    }

    /** What a merged jet carries: the running mean and mean square of its pairwise distances. */
    public static final class JetInfo {
        private final double meanR;
        private final double msR;
        private final double wt;
        private double rd;

        public JetInfo(double meanR, double msR, double wt) {
            this.meanR = meanR;
            this.msR = msR;
            this.wt = wt;
        }

        public double meanR() { return meanR; }
        public double msR() { return msR; }
        public double wt() { return wt; }
        public void setRd(double v) { rd = v; }
        /** The dynamic radius of the jet as it stands. */
        public double Rd() { return rd; }
        public double Rfin() { return rd; }

        /** The pt-weighted standard deviation of the pairwise distances, signed as the C++ keeps it. */
        public double sigma() {
            final double v = msR - meanR * meanR;
            return v > 0.0 ? Math.sqrt(v) : -Math.sqrt(-v);
        }
    }

    private final double radius;
    private final Algorithm algorithm;
    private final RecombinationScheme scheme;

    public DynamicR(double radius, Algorithm algorithm, RecombinationScheme scheme) {
        this.radius = radius;
        this.algorithm = algorithm;
        this.scheme = scheme;
    }

    public DynamicR(double radius, Algorithm algorithm) {
        this(radius, algorithm, RecombinationScheme.E_SCHEME);
    }

    /** Anti-kt-like, the default. */
    public DynamicR(double radius) {
        this(radius, Algorithm.DRAK);
    }

    public static DynamicR drak(double radius) { return new DynamicR(radius, Algorithm.DRAK); }
    public static DynamicR drca(double radius) { return new DynamicR(radius, Algorithm.DRCA); }
    public static DynamicR drkt(double radius) { return new DynamicR(radius, Algorithm.DRKT); }

    @Override public double R() { return radius; }
    public Algorithm algorithm() { return algorithm; }
    public RecombinationScheme recombinationScheme() { return scheme; }

    @Override
    public String description() {
        final String kind = switch (algorithm) {
            case DRKT -> "KT";
            case DRCA -> "CA";
            case DRAK -> "AK";
        };
        return "Dynamic Radius (" + kind + ") Jet algorithm with R0 = " + Fmt.g(R());
    }

    /** The dynamic radius of a jet, R0 when it carries no information (a single particle). */
    public double dynamicRadius(PseudoJet jet) {
        return jet.hasUserInfo(JetInfo.class) ? jet.userInfo(JetInfo.class).Rd() : radius;
    }

    /* ------------------------------------------------------------------ */
    /* Brief jets                                                          */
    /* ------------------------------------------------------------------ */

    private abstract static class Brief<B extends Brief<B>> implements NNBriefJet<B> {
        final PseudoJet jet;
        final double pt2;
        final double sdR;
        final double r;

        Brief(PseudoJet jet, double r0) {
            this.jet = jet;
            this.pt2 = jet.pt2();
            this.r = r0;
            if (jet.hasUserInfo(JetInfo.class)) {
                final JetInfo info = jet.userInfo(JetInfo.class);
                final double avg = info.meanR();
                final double sd = info.msR() - avg * avg;
                this.sdR = sd > 0.0 ? Math.sqrt(sd) : -Math.sqrt(-sd);
            } else {
                this.sdR = 0.0;
            }
        }
    }

    private static final class AK extends Brief<AK> {
        AK(PseudoJet j, double r0) { super(j, r0); }

        @Override
        public double distance(AK o) {
            final double d2 = o.jet.plainDistance(jet);
            double pt2max = o.pt2;
            if (pt2max < pt2) pt2max = pt2;
            return d2 / pt2max;
        }

        @Override
        public double beamDistance() {
            final double num = r + sdR;
            return num * num / pt2;
        }
    }

    private static final class CA extends Brief<CA> {
        CA(PseudoJet j, double r0) { super(j, r0); }

        @Override
        public double distance(CA o) {
            return o.jet.plainDistance(jet);
        }

        @Override
        public double beamDistance() {
            final double num = r + sdR;
            return num * num;
        }
    }

    private static final class KT extends Brief<KT> {
        KT(PseudoJet j, double r0) { super(j, r0); }

        @Override
        public double distance(KT o) {
            final double d2 = o.jet.plainDistance(jet);
            double pt2min = o.pt2;
            if (pt2min > pt2) pt2min = pt2;
            return d2 * pt2min;
        }

        @Override
        public double beamDistance() {
            final double num = r + sdR;
            return num * num * pt2;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Clustering                                                          */
    /* ------------------------------------------------------------------ */

    @Override
    public void runClustering(ClusterSequence cs) {
        switch (algorithm) {
            case DRKT -> run(cs, j -> new KT(j, radius));
            case DRCA -> run(cs, j -> new CA(j, radius));
            case DRAK -> run(cs, j -> new AK(j, radius));
        }
    }

    private <B extends Brief<B>> void run(ClusterSequence cs, Function<PseudoJet, B> factory) {
        int njets = cs.nJets();
        final int insize = njets;
        final NNH<B> nn = new NNH<>(cs.jets(), factory);
        final int[] ab = new int[2];
        while (njets > 0) {
            final double dij = nn.dijMin(ab);
            final int i = ab[0];
            final int j = ab[1];
            if (j >= 0) {
                final PseudoJet jetI = cs.pluginNonConstJet(i);
                final PseudoJet jetJ = cs.pluginNonConstJet(j);
                double meanDR;
                double msDR;
                double wt;
                if (i < insize && j < insize) {
                    // two particles: their own separation
                    msDR = jetI.plainDistance(jetJ);
                    meanDR = Math.sqrt(msDR);
                    wt = jetI.pt() * jetJ.pt();
                } else if (i < insize) {
                    // particle i, jet j: i against every constituent of j, then j's own record
                    final double[] s = crossTerms(jetI, jetJ.constituents());
                    final JetInfo info = jetJ.userInfo(JetInfo.class);
                    wt = s[2] + info.wt();
                    meanDR = (s[0] + info.meanR() * info.wt()) / wt;
                    msDR = (s[1] + info.msR() * info.wt()) / wt;
                } else if (j < insize) {
                    final double[] s = crossTerms(jetJ, jetI.constituents());
                    final JetInfo info = jetI.userInfo(JetInfo.class);
                    wt = s[2] + info.wt();
                    meanDR = (s[0] + info.meanR() * info.wt()) / wt;
                    msDR = (s[1] + info.msR() * info.wt()) / wt;
                } else {
                    // two jets: every pair of constituents, then both records
                    final List<PseudoJet> ci = jetI.constituents();
                    final List<PseudoJet> cj = jetJ.constituents();
                    final double[] ptis = new double[ci.size()];
                    final double[] ptjs = new double[cj.size()];
                    double wti = 0;
                    double wtj = 0;
                    for (int it = 0; it < ci.size(); it++) {
                        ptis[it] = ci.get(it).pt();
                        wti = wti + ptis[it];
                    }
                    for (int jt = 0; jt < cj.size(); jt++) {
                        ptjs[jt] = cj.get(jt).pt();
                        wtj = wtj + ptjs[jt];
                    }
                    meanDR = 0;
                    msDR = 0;
                    for (int it = 0; it < ci.size(); it++) {
                        double mean1 = 0;
                        double ms1 = 0;
                        for (int jt = 0; jt < cj.size(); jt++) {
                            final double d2 = ci.get(it).plainDistance(cj.get(jt));
                            ms1 += d2 * ptjs[jt];
                            mean1 += Math.sqrt(d2) * ptjs[jt];
                        }
                        meanDR = meanDR + mean1 * ptis[it];
                        msDR = msDR + ms1 * ptis[it];
                    }
                    final JetInfo ii = jetI.userInfo(JetInfo.class);
                    final JetInfo jj = jetJ.userInfo(JetInfo.class);
                    wt = wti * wtj + ii.wt() + jj.wt();
                    meanDR = (meanDR + ii.meanR() * ii.wt() + jj.meanR() * jj.wt()) / wt;
                    msDR = (msDR + ii.msR() * ii.wt() + jj.msR() * jj.wt()) / wt;
                }
                final PseudoJet newjet = jetI.plus(jetJ);
                final JetInfo info = new JetInfo(meanDR, msDR, wt);
                info.setRd(calculateRd(meanDR, msDR));
                newjet.setUserInfo(info);
                final int k = cs.pluginRecordIJRecombination(i, j, dij, newjet);
                nn.mergeJets(i, j, cs.jet(k), k);
            } else {
                cs.pluginRecordIBRecombination(i, dij);
                nn.removeJet(i);
            }
            njets--;
        }
    }

    /** sum sqrt(d) pt, sum d pt and sum pt over the constituents, each times the particle's pt. */
    private static double[] crossTerms(PseudoJet particle, List<PseudoJet> constituents) {
        final double pt = particle.pt();
        double mean = 0;
        double ms = 0;
        double wt = 0;
        for (PseudoJet c : constituents) {
            final double d2 = particle.plainDistance(c);
            final double ptc = c.pt();
            ms += d2 * ptc;
            mean += Math.sqrt(d2) * ptc;
            wt += ptc;
        }
        return new double[]{mean * pt, ms * pt, wt * pt};
    }

    /** R0 + sigma, sigma signed. */
    double calculateRd(double meanDR, double msDR) {
        final double v = msDR - meanDR * meanDR;
        return v > 0.0 ? radius + Math.sqrt(v) : radius - Math.sqrt(-v);
    }
}
