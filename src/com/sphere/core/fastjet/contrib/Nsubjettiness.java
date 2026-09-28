package com.sphere.core.fastjet.contrib;

import com.sphere.core.fastjet.Citations;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.DD;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.RecombinationScheme;

import java.util.ArrayList;
import java.util.List;

/**
 * N-subjettiness (J. Thaler, K. Van Tilburg, JHEP 03 (2011) 015; JHEP 02
 * (2012) 093): tau_N = sum_k pt_k min_a (Delta R_{k,a})^beta, normalised by
 * sum_k pt_k R0^beta, with N axes from an exclusive reclustering of the
 * constituents, optionally improved by a one-pass minimisation (each axis
 * moved to the weighted centre of the particles nearest to it, while tau
 * decreases).
 *
 * Written for Sphere from the papers; sums are carried to 106 bits.
 */
public class Nsubjettiness implements FunctionOfPseudoJet<Double> {

    static {
        Citations.use("nsubjettiness"); // listed in the console's Citations menu once used
    }

    /** How the axes are chosen. */
    public enum Axes {
        /** Exclusive kt subjets, E-scheme. */
        KT,
        /** Exclusive kt subjets, winner-take-all recombination (recoil free). */
        WTA_KT,
        /** Exclusive Cambridge/Aachen subjets. */
        CA,
        /** Exclusive C/A subjets, winner-take-all. */
        WTA_CA,
        /** kt axes improved by the one-pass minimisation. */
        ONEPASS_KT,
        /** WTA kt axes improved by the one-pass minimisation. */
        ONEPASS_WTA_KT
    }

    private final int n;
    private final double beta;
    private final double r0;
    private final Axes axes;
    private final boolean normalised;

    /** tau_N with beta = 1, R0 = 1, WTA kt axes, normalised. */
    public Nsubjettiness(int n) {
        this(n, 1.0, 1.0, Axes.WTA_KT, true);
    }

    public Nsubjettiness(int n, double beta, double r0, Axes axes, boolean normalised) {
        if (n < 1) throw new IllegalArgumentException("N-subjettiness needs N >= 1");
        this.n = n;
        this.beta = beta;
        this.r0 = r0;
        this.axes = axes;
        this.normalised = normalised;
    }

    @Override
    public String description() {
        return "N-subjettiness tau_" + n + " with beta = " + Fmt.g(beta) + ", R0 = " + Fmt.g(r0) + ", " + axes
            + " axes, " + (normalised ? "normalised" : "unnormalised");
    }

    @Override
    public Double result(PseudoJet jet) {
        return tau(jet.constituents(), axes(jet.constituents()));
    }

    /** The axes the measure uses for these particles. */
    public List<PseudoJet> axes(List<PseudoJet> particles) {
        if (particles.size() <= n) return new ArrayList<>(particles);
        final boolean wta = axes == Axes.WTA_KT || axes == Axes.WTA_CA || axes == Axes.ONEPASS_WTA_KT;
        final JetAlgorithm alg = (axes == Axes.CA || axes == Axes.WTA_CA) ? JetAlgorithm.CAMBRIDGE : JetAlgorithm.KT;
        final JetDefinition def = new JetDefinition(alg, JetDefinition.MAX_ALLOWABLE_R,
            wta ? RecombinationScheme.WTA_PT_SCHEME : RecombinationScheme.E_SCHEME);
        if (!particles.isEmpty()) def.setPrecision(particles.get(0).precision());
        List<PseudoJet> ax = new ClusterSequence(particles, def).exclusiveJets(n);
        if (axes == Axes.ONEPASS_KT || axes == Axes.ONEPASS_WTA_KT) ax = onePass(particles, ax);
        return ax;
    }

    /** tau_N of these particles about these axes. */
    public double tau(List<PseudoJet> particles, List<PseudoJet> ax) {
        if (particles.size() <= n || ax.isEmpty()) return 0.0;
        DD num = DD.ZERO;
        DD den = DD.ZERO;
        final DD r0b = Kin.pow(new DD(r0), beta);
        for (PseudoJet p : particles) {
            final DD pt = Kin.pt(p);
            DD best = null;
            for (PseudoJet a : ax) {
                final DD d2 = Kin.dR2(p, a);
                if (best == null || d2.lt(best)) best = d2;
            }
            num = num.add(pt.mul(Kin.pow(best, beta / 2)));
            den = den.add(pt.mul(r0b));
        }
        return normalised ? (den.isZero() ? 0.0 : num.div(den).doubleValue()) : num.doubleValue();
    }

    /** The one-pass minimisation: Lloyd-like moves of the axes while tau decreases. */
    private List<PseudoJet> onePass(List<PseudoJet> particles, List<PseudoJet> start) {
        List<PseudoJet> best = start;
        double bestTau = tau(particles, start);
        List<PseudoJet> current = start;
        for (int iter = 0; iter < 100; iter++) {
            final double[] sy = new double[current.size()];
            final double[] sp = new double[current.size()];
            final double[] sw = new double[current.size()];
            for (PseudoJet p : particles) {
                int a = -1;
                double dmin = Double.MAX_VALUE;
                for (int k = 0; k < current.size(); k++) {
                    final double d2 = p.plainDistance(current.get(k));
                    if (d2 < dmin) {
                        dmin = d2;
                        a = k;
                    }
                }
                final double w = p.pt() * (dmin > 0 ? Math.pow(dmin, (beta - 2) / 2) : (beta >= 2 ? 0 : 1e30));
                final PseudoJet ax = current.get(a);
                double dphi = p.phi() - ax.phi();
                if (dphi > Math.PI) dphi -= 2 * Math.PI;
                if (dphi < -Math.PI) dphi += 2 * Math.PI;
                sy[a] += w * (p.rap() - ax.rap());
                sp[a] += w * dphi;
                sw[a] += w;
            }
            final List<PseudoJet> next = new ArrayList<>(current.size());
            for (int k = 0; k < current.size(); k++) {
                final PseudoJet ax = current.get(k);
                if (sw[k] == 0) {
                    next.add(ax);
                    continue;
                }
                final PseudoJet moved = new PseudoJet();
                moved.resetMomentumPtYPhiM(Math.max(ax.pt(), 1e-300), ax.rap() + sy[k] / sw[k], ax.phi() + sp[k] / sw[k], 0.0);
                next.add(moved);
            }
            final double t = tau(particles, next);
            if (!(t < bestTau * (1 - 1e-12))) break;
            bestTau = t;
            best = next;
            current = next;
        }
        return best;
    }

    /** The ratio tau_N / tau_{N-1} (tau21, tau32...), with the same settings. */
    public static FunctionOfPseudoJet<Double> ratio(int n, double beta, Axes axes) {
        final Nsubjettiness num = new Nsubjettiness(n, beta, 1.0, axes, true);
        final Nsubjettiness den = new Nsubjettiness(n - 1, beta, 1.0, axes, true);
        return FunctionOfPseudoJet.of("tau" + n + (n - 1) + " (beta=" + Fmt.g(beta) + ", " + axes + ")", jet -> {
            final double d = den.result(jet);
            return d == 0 ? 0.0 : num.result(jet) / d;
        });
    }
}
