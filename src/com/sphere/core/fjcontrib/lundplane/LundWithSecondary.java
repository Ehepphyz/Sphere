package com.sphere.core.fjcontrib.lundplane;

import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;

import java.util.ArrayList;
import java.util.List;

/**
 * The primary and a secondary Lund plane, LundWithSecondary: the secondary
 * plane is that of the softer branch of the primary declustering the
 * {@link SecondaryLund} picks.
 */
public class LundWithSecondary {

    private final LundGenerator lundGen;
    private final SecondaryLund secondaryDef;

    public LundWithSecondary(SecondaryLund secondaryDef) {
        this.lundGen = new LundGenerator();
        this.secondaryDef = secondaryDef;
    }

    public LundWithSecondary(JetAlgorithm jetAlg, SecondaryLund secondaryDef) {
        this.lundGen = new LundGenerator(jetAlg);
        this.secondaryDef = secondaryDef;
    }

    public LundWithSecondary(JetDefinition jetDef, SecondaryLund secondaryDef) {
        this.lundGen = new LundGenerator(jetDef);
        this.secondaryDef = secondaryDef;
    }

    public List<LundDeclustering> primary(PseudoJet jet) {
        return lundGen.result(jet);
    }

    /** The secondary plane, computing the primary one again. */
    public List<LundDeclustering> secondary(PseudoJet jet) {
        return secondary(lundGen.result(jet));
    }

    public List<LundDeclustering> secondary(List<LundDeclustering> declusts) {
        final int secIndex = secondaryIndex(declusts);
        return secIndex >= 0 ? lundGen.result(declusts.get(secIndex).softer()) : new ArrayList<>();
    }

    public int secondaryIndex(List<LundDeclustering> declusts) {
        if (secondaryDef == null) {
            throw new FastJetException("secondary class is a null pointer, cannot identify element to use for secondary plane");
        }
        return secondaryDef.result(declusts);
    }

    public String description() {
        return "LundWithSecondary using " + secondaryDef.description() + " and " + lundGen.description();
    }
}
