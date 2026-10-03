package com.sphere.core.fjcontrib.lundplane;

import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.tools.Recluster;
import com.sphere.core.fjcontrib.ContribCitations;

import java.util.ArrayList;
import java.util.List;

/**
 * The primary Lund plane of a jet, LundGenerator (LundPlane 2.1.2;
 * F.A. Dreyer, G.P. Salam and G. Soyez, JHEP 12 (2018) 064): the jet
 * reclustered (C/A by default) and declustered along its harder branch,
 * one {@link LundDeclustering} per step.
 */
public class LundGenerator implements FunctionOfPseudoJet<List<LundDeclustering>> {

    static {
        ContribCitations.use("lund");
    }

    private final Recluster recluster;

    public LundGenerator() {
        this(JetAlgorithm.CAMBRIDGE);
    }

    public LundGenerator(JetAlgorithm jetAlg) {
        this(new JetDefinition(jetAlg, JetDefinition.MAX_ALLOWABLE_R));
    }

    public LundGenerator(JetDefinition jetDef) {
        recluster = new Recluster(jetDef);
    }

    @Override
    public List<LundDeclustering> result(PseudoJet jet) {
        final List<LundDeclustering> result = new ArrayList<>();
        PseudoJet pair = recluster.result(jet);
        PseudoJet[] parents;
        while ((parents = pair.parents()) != null) {
            PseudoJet j1 = parents[0];
            PseudoJet j2 = parents[1];
            if (j1.pt2() < j2.pt2()) {
                final PseudoJet t = j1;
                j1 = j2;
                j2 = t;
            }
            result.add(new LundDeclustering(pair, j1, j2));
            pair = j1;
        }
        return result;
    }

    @Override
    public String description() {
        return "LundGenerator with " + recluster.description();
    }
}
