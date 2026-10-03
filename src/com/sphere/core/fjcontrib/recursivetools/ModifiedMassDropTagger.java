package com.sphere.core.fjcontrib.recursivetools;

import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.ContribCitations;

/**
 * The modified mass-drop tagger, fastjet::contrib::ModifiedMassDropTagger
 * (M. Dasgupta, A. Fregoso, S. Marzani and G.P. Salam, JHEP 09 (2013) 029):
 * the declustering of {@link RecursiveSymmetryCutBase} with the fixed cut
 * z &gt; z_cut. A tagger: an empty jet when no splitting passes.
 */
public class ModifiedMassDropTagger extends RecursiveSymmetryCutBase {

    static {
        ContribCitations.use("mmdt");
    }

    protected final double symmetryCut;

    public ModifiedMassDropTagger(double symmetryCut) {
        this(symmetryCut, (FunctionOfPseudoJet<PseudoJet>) null);
    }

    public ModifiedMassDropTagger(double symmetryCut, FunctionOfPseudoJet<PseudoJet> subtractor) {
        super(SymmetryMeasure.SCALAR_Z, Double.POSITIVE_INFINITY, RecursionChoice.LARGER_PT, subtractor);
        this.symmetryCut = symmetryCut;
    }

    public ModifiedMassDropTagger(double symmetryCut, SymmetryMeasure symmetryMeasure, double muCut,
                                  RecursionChoice recursionChoice, FunctionOfPseudoJet<PseudoJet> subtractor) {
        super(symmetryMeasure, muCut, recursionChoice, subtractor);
        this.symmetryCut = symmetryCut;
    }

    public ModifiedMassDropTagger(double symmetryCut, SymmetryMeasure symmetryMeasure) {
        this(symmetryCut, symmetryMeasure, Double.POSITIVE_INFINITY, RecursionChoice.LARGER_PT, null);
    }

    public ModifiedMassDropTagger(double symmetryCut, SymmetryMeasure symmetryMeasure, double muCut) {
        this(symmetryCut, symmetryMeasure, muCut, RecursionChoice.LARGER_PT, null);
    }

    public double symmetryCut() { return symmetryCut; }

    @Override
    protected double symmetryCutFn(PseudoJet p1, PseudoJet p2, double r0sqr) {
        return symmetryCut;
    }

    @Override
    protected String symmetryCutDescription() {
        return Fmt.g(symmetryCut) + " [ModifiedMassDropTagger]";
    }
}
