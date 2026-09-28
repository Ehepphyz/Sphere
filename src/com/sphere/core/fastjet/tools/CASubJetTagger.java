package com.sphere.core.fastjet.tools;

import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.LimitedWarning;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.WrappedStructure;

/**
 * The Cambridge/Aachen subjet tagger of Butterworth, Ellis, Rizzi and Salam,
 * fastjet::CASubJetTagger: through every splitting of the C/A tree that
 * passes the z cut, find the one that maximises a chosen distance.
 */
public class CASubJetTagger implements Transformer {

    public enum ScaleChoice { KT2_DISTANCE, JADE_DISTANCE, JADE2_DISTANCE, PLAIN_DISTANCE,
        MASS_DROP_DISTANCE, DOT_PRODUCT_DISTANCE }

    private static final LimitedWarning NON_CA_WARNINGS = new LimitedWarning();

    /** The tagged jet's structure. */
    public static final class CASubJetTaggerStructure extends WrappedStructure {
        ScaleChoice scaleChoice;
        double distance;
        boolean absoluteZ;
        double z;

        CASubJetTaggerStructure(PseudoJet resultJet) {
            super(resultJet.structure());
        }

        public ScaleChoice scaleChoice() { return scaleChoice; }
        public double distance() { return distance; }
        public double z() { return z; }
        public boolean absoluteZ() { return absoluteZ; }
    }

    private static final class JetAux {
        PseudoJet jet;
        double auxDistance;
        double deltaR;
        double z;
    }

    private final ScaleChoice scaleChoice;
    private final double zThreshold;
    private double dr2Min;
    private boolean absoluteZCut;

    public CASubJetTagger(ScaleChoice scaleChoice, double zThreshold) {
        this.scaleChoice = scaleChoice;
        this.zThreshold = zThreshold;
    }

    public CASubJetTagger() {
        this(ScaleChoice.JADE_DISTANCE, 0.1);
    }

    public void setDrMin(double drmin) {
        dr2Min = drmin * drmin;
    }

    public void setAbsoluteZCut(boolean abs) {
        absoluteZCut = abs;
    }

    @Override
    public String description() {
        return "CASubJetTagger with z_threshold=" + Fmt.g(zThreshold)
            + (absoluteZCut ? " (defined wrt original jet)" : "")
            + " and scale choice " + scaleChoice.name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public PseudoJet result(PseudoJet jet) {
        if (jet.validatedCs().jetDef().jetAlgorithm() != JetAlgorithm.CAMBRIDGE) {
            NON_CA_WARNINGS.warn("CASubJetTagger should only be applied on jets from a Cambridge/Aachen clustering; use it with other algorithms at your own risk");
        }
        final JetAux aux = new JetAux();
        aux.jet = new PseudoJet();
        aux.auxDistance = -Double.MAX_VALUE;
        aux.deltaR = 0.0;
        aux.z = 1.0;
        recurse(jet, aux, jet);
        final PseudoJet res = aux.jet;
        if (!res.hasStructure() && res.isZero()) {
            return res;
        }
        final CASubJetTaggerStructure s = new CASubJetTaggerStructure(res);
        s.scaleChoice = scaleChoice;
        s.distance = aux.auxDistance;
        s.absoluteZ = absoluteZCut;
        s.z = aux.z;
        res.setStructure(s);
        return res;
    }

    private void recurse(PseudoJet jet, JetAux aux, PseudoJet original) {
        final PseudoJet[] parents = jet.parents();
        if (parents == null) return;
        PseudoJet p1 = parents[0];
        PseudoJet p2 = parents[1];
        if (p1.squaredDistance(p2) < dr2Min) return;
        final double dist = switch (scaleChoice) {
            case KT2_DISTANCE -> p1.ktDistance(p2);
            case JADE_DISTANCE -> p1.perp() * p2.perp() * p1.squaredDistance(p2);
            case JADE2_DISTANCE -> p1.perp() * p2.perp() * Math.pow(p1.squaredDistance(p2), 2);
            case PLAIN_DISTANCE -> p1.squaredDistance(p2);
            case MASS_DROP_DISTANCE -> jet.m() - Math.max(p1.m(), p2.m());
            case DOT_PRODUCT_DISTANCE -> PseudoJet.dotProduct(p1, p2);
        };
        boolean zcut1 = true;
        double z2;
        if (p1.perp2() < p2.perp2()) {
            final PseudoJet t = p1;
            p1 = p2;
            p2 = t;
        }
        if (absoluteZCut) {
            z2 = p2.perp() / original.perp();
            zcut1 = p1.perp() / original.perp() >= zThreshold;
        } else {
            z2 = p2.perp() / (p1.perp() + p2.perp());
        }
        final boolean zcut2 = z2 >= zThreshold;
        if (zcut1 && zcut2) {
            if (dist > aux.auxDistance) {
                aux.jet = jet.copy();
                aux.auxDistance = dist;
                aux.deltaR = Math.sqrt(p1.squaredDistance(p2));
                aux.z = z2;
            }
        }
        if (zcut1) recurse(p1, aux, original);
        if (zcut2) recurse(p2, aux, original);
    }
}
