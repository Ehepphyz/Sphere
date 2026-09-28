package com.sphere.core.fastjet.tools;

import com.sphere.core.fastjet.Citations;

import com.sphere.core.fastjet.CompositeJetStructure;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.LimitedWarning;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Recombiner;

import java.util.ArrayList;
import java.util.List;

/**
 * The Johns Hopkins top tagger (Kaplan, Rehermann, Schwartz, Tweedie),
 * fastjet::JHTopTagger: two levels of declustering into up to four subjets,
 * the pair closest to the W mass forming the W, then cuts on the helicity
 * angle and on user selectors.
 */
public class JHTopTagger extends TopTaggerBase {

    static {
        Citations.use("jhtop"); // listed in the console's Citations menu once used
    }

    private static final LimitedWarning WARNINGS_NONCA = new LimitedWarning();

    /** A tagged top: pieces W and non-W. */
    public static final class JHTopTaggerStructure extends CompositeJetStructure
            implements TopTaggerBase.TopTaggerBaseStructure {
        double cosThetaW;

        JHTopTaggerStructure(List<PseudoJet> pieces, Recombiner rec) {
            super(pieces, rec);
        }

        @Override
        public PseudoJet W() {
            return pieces.get(0).copy();
        }

        public PseudoJet W1() {
            return W().pieces().get(0);
        }

        public PseudoJet W2() {
            return W().pieces().get(1);
        }

        @Override
        public PseudoJet nonW() {
            return pieces.get(1).copy();
        }

        public double cosThetaW() {
            return cosThetaW;
        }
    }

    private final double deltaP, deltaR, cosThetaWMax, mW;

    public JHTopTagger(double deltaP, double deltaR, double cosThetaWMax, double mW) {
        this.deltaP = deltaP;
        this.deltaR = deltaR;
        this.cosThetaWMax = cosThetaWMax;
        this.mW = mW;
    }

    public JHTopTagger() {
        this(0.10, 0.19, 0.7, 80.4);
    }

    @Override
    public String description() {
        return "JHTopTagger with delta_p=" + Fmt.g(deltaP) + ", delta_r=" + Fmt.g(deltaR)
            + ", cos_theta_W_max=" + Fmt.g(cosThetaWMax) + " and mW = " + Fmt.g(mW) + descriptionOfSelectors();
    }

    @Override
    public PseudoJet result(PseudoJet jet) {
        if (!jet.hasValidClusterSequence()) {
            throw new FastJetException("JHTopTagger can only be applied on jets having an associated (and valid) ClusterSequence");
        }
        if (jet.validatedCs().jetDef().jetAlgorithm() != JetAlgorithm.CAMBRIDGE) {
            WARNINGS_NONCA.warn("JHTopTagger should only be applied on jets from a Cambridge/Aachen clustering; use it with other algorithms at your own risk.");
        }
        final List<PseudoJet> split0 = splitOnce(jet, jet);
        if (split0.isEmpty()) {
            return new PseudoJet();
        }
        final List<PseudoJet> subjets = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final List<PseudoJet> split1 = splitOnce(split0.get(i), jet);
            if (!split1.isEmpty()) {
                subjets.add(split1.get(0));
                subjets.add(split1.get(1));
            } else {
                subjets.add(split0.get(i));
            }
        }
        if (subjets.size() < 3) {
            return new PseudoJet();
        }
        double dmWMin = Double.MAX_VALUE;
        int ii = -1;
        int jj = -1;
        for (int i = 0; i < subjets.size() - 1; i++) {
            for (int j = i + 1; j < subjets.size(); j++) {
                final double dmW = Math.abs(mW - subjets.get(i).plus(subjets.get(j)).m());
                if (dmW < dmWMin) {
                    dmWMin = dmW;
                    ii = i;
                    jj = j;
                }
            }
        }
        if (ii > 0) swap(subjets, ii, 0);
        if (jj > 1) swap(subjets, jj, 1);
        if (subjets.get(0).perp2() < subjets.get(1).perp2()) swap(subjets, 0, 1);
        if (subjets.size() > 3 && subjets.get(2).perp2() < subjets.get(3).perp2()) swap(subjets, 2, 3);

        final Recombiner rec = jet.associatedClusterSequence().jetDef().recombiner();
        final PseudoJet w = PseudoJet.join(List.of(subjets.get(0), subjets.get(1)), rec);
        final PseudoJet nonW = subjets.size() > 3
            ? PseudoJet.join(List.of(subjets.get(2), subjets.get(3)), rec)
            : PseudoJet.join(List.of(subjets.get(2)), rec);
        final PseudoJet resultLocal = PseudoJet.join(List.of(w, nonW), rec);
        final JHTopTaggerStructure s = new JHTopTaggerStructure(List.of(w, nonW), rec);
        resultLocal.setStructure(s);
        s.cosThetaW = cosThetaW(resultLocal);
        if (s.cosThetaW >= cosThetaWMax || !topSelector.pass(resultLocal) || !wSelector.pass(w)) {
            resultLocal.timesEqual(0.0);
        }
        return resultLocal;
    }

    private static void swap(List<PseudoJet> l, int a, int b) {
        final PseudoJet t = l.get(a);
        l.set(a, l.get(b));
        l.set(b, t);
    }

    private List<PseudoJet> splitOnce(PseudoJet jetToSplit, PseudoJet referenceJet) {
        PseudoJet thisJet = jetToSplit;
        final List<PseudoJet> res = new ArrayList<>();
        PseudoJet[] parents;
        while ((parents = thisJet.parents()) != null) {
            PseudoJet p1 = parents[0];
            PseudoJet p2 = parents[1];
            if (p2.perp2() > p1.perp2()) {
                final PseudoJet t = p1;
                p1 = p2;
                p2 = t;
            }
            if (p1.perp() < deltaP * referenceJet.perp()) break;
            if (Math.abs(p2.rap() - p1.rap()) + Math.abs(p2.deltaPhiTo(p1)) < deltaR) break;
            if (p2.perp() < deltaP * referenceJet.perp()) {
                thisJet = p1;
                continue;
            }
            res.add(p1);
            res.add(p2);
            break;
        }
        return res;
    }
}
