package com.sphere.core.fjcontrib.recursivetools;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.DD;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.ContribCitations;

/**
 * Soft drop, fastjet::contrib::SoftDrop (A.J. Larkoski, S. Marzani, G. Soyez
 * and J. Thaler, JHEP 05 (2014) 146): the declustering of
 * {@link RecursiveSymmetryCutBase} with the cut
 * z &gt; z_cut (theta/R0)^beta. A groomer by default; beta &lt; 0 is meant
 * for tagging mode.
 */
public class SoftDrop extends RecursiveSymmetryCutBase {

    static {
        ContribCitations.use("softdrop");
    }

    protected final double beta;
    protected final double symmetryCut;
    protected final double r0sqr;

    /** The simplified constructor: scalar z, no mass drop, larger pt followed. */
    public SoftDrop(double beta, double symmetryCut) {
        this(beta, symmetryCut, 1.0, null);
    }

    public SoftDrop(double beta, double symmetryCut, double r0) {
        this(beta, symmetryCut, r0, null);
    }

    public SoftDrop(double beta, double symmetryCut, double r0, FunctionOfPseudoJet<PseudoJet> subtractor) {
        super(SymmetryMeasure.SCALAR_Z, Double.POSITIVE_INFINITY, RecursionChoice.LARGER_PT, subtractor);
        this.beta = beta;
        this.symmetryCut = symmetryCut;
        this.r0sqr = r0 * r0;
        setGroomingMode(true);
    }

    /** The full constructor. */
    public SoftDrop(double beta, double symmetryCut, SymmetryMeasure symmetryMeasure, double r0, double muCut,
                    RecursionChoice recursionChoice, FunctionOfPseudoJet<PseudoJet> subtractor) {
        super(symmetryMeasure, muCut, recursionChoice, subtractor);
        this.beta = beta;
        this.symmetryCut = symmetryCut;
        this.r0sqr = r0 * r0;
        setGroomingMode(true);
    }

    public SoftDrop(double beta, double symmetryCut, SymmetryMeasure symmetryMeasure, double r0) {
        this(beta, symmetryCut, symmetryMeasure, r0, Double.POSITIVE_INFINITY, RecursionChoice.LARGER_PT, null);
    }

    public double beta() { return beta; }
    public double symmetryCut() { return symmetryCut; }
    public double R0() { return Math.sqrt(r0sqr); }

    @Override
    protected double symmetryCutFn(PseudoJet p1, PseudoJet p2, double optionalR0sqr) {
        final double r0s = Double.isNaN(optionalR0sqr) ? r0sqr : optionalR0sqr;
        return symmetryCut * CRMath.pow(squaredGeometricDistance(p1, p2) / r0s, 0.5 * beta);
    }

    @Override
    protected DD symmetryCutFnDD(PseudoJet p1, PseudoJet p2, double optionalR0sqr) {
        final double r0s = Double.isNaN(optionalR0sqr) ? r0sqr : optionalR0sqr;
        final DD x = squaredGeometricDistanceDD(p1, p2).div(r0s);
        final DD f;
        if (beta == 0) f = DD.ONE;
        else if (beta == 2) f = x;
        else if (beta == 1) f = x.sqrt();
        else f = x.isZero() ? (beta > 0 ? DD.ZERO : new DD(Double.POSITIVE_INFINITY)) : x.pow(0.5 * beta);
        return f.mul(symmetryCut);
    }

    @Override
    protected String symmetryCutDescription() {
        return Fmt.g(symmetryCut) + " (theta/" + Fmt.g(Math.sqrt(r0sqr)) + ")^" + Fmt.g(beta) + " [SoftDrop]";
    }
}
