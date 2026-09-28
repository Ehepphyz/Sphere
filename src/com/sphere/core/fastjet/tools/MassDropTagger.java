package com.sphere.core.fastjet.tools;

import com.sphere.core.fastjet.Citations;

import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.LimitedWarning;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.WrappedStructure;

/**
 * The BDRS mass-drop tagger, fastjet::MassDropTagger: undo the C/A clustering
 * of a jet, following the heavier branch, until a splitting shows a large mass
 * drop (m1 < mu m) and is not too asymmetric (y > ycut).
 */
public class MassDropTagger implements Transformer {

    static {
        Citations.use("filter"); // listed in the console's Citations menu once used
    }

    private static final LimitedWarning WARNINGS_NONCA = new LimitedWarning();
    private static final LimitedWarning NEGATIVE_MASS_WARNING = new LimitedWarning();

    /** The tagged jet's structure, with the mass drop and asymmetry found. */
    public static final class MassDropTaggerStructure extends WrappedStructure {
        double mu;
        double y;

        MassDropTaggerStructure(PseudoJet resultJet) {
            super(resultJet.structure());
        }

        public double mu() { return mu; }
        public double y() { return y; }
    }

    private final double mu;
    private final double ycut;

    public MassDropTagger(double mu, double ycut) {
        this.mu = mu;
        this.ycut = ycut;
    }

    public MassDropTagger() {
        this(0.67, 0.09);
    }

    @Override
    public String description() {
        return "MassDropTagger with mu=" + Fmt.g(mu) + " and ycut=" + Fmt.g(ycut);
    }

    @Override
    public PseudoJet result(PseudoJet jet) {
        PseudoJet j = jet.copy();
        if (!j.hasAssociatedClusterSequence()
                || j.validatedCs().jetDef().jetAlgorithm() != JetAlgorithm.CAMBRIDGE) {
            WARNINGS_NONCA.warn("MassDropTagger should only be applied on jets from a Cambridge/Aachen clustering; use it with other algorithms at your own risk.");
        }
        PseudoJet[] parents = null;
        PseudoJet j1 = null;
        PseudoJet j2 = null;
        while ((parents = j.parents()) != null) {
            j1 = parents[0];
            j2 = parents[1];
            if (j.m2() <= 0) {
                NEGATIVE_MASS_WARNING.warn("MassDropTagger: parent (sub)jet has mass^2<=0; returning null jet");
                return new PseudoJet();
            }
            if (j1.m2() < j2.m2()) {
                final PseudoJet t = j1;
                j1 = j2;
                j2 = t;
            }
            if (j1.m2() < mu * mu * j.m2() && j1.ktDistance(j2) > ycut * j.m2()) {
                break;
            }
            j = j1;
        }
        if (parents == null) {
            return new PseudoJet();
        }
        final PseudoJet resultLocal = j.copy();
        final MassDropTaggerStructure s = new MassDropTaggerStructure(resultLocal);
        s.mu = j1.m() / j.m();
        s.y = j1.ktDistance(j2) / j.m2();
        resultLocal.setStructure(s);
        return resultLocal;
    }
}
