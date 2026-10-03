package com.sphere.core.fjcontrib.nsubjettiness;

import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.WrappedStructure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything an N-(sub)jettiness calculation found, TauComponents: tau and
 * its jet and beam pieces, numerators and denominator, the jets (regions)
 * and the axes.
 */
public class TauComponents {

    /** Whether tau has a beam region and a denominator. */
    public enum TauMode {
        UNDEFINED_SHAPE,
        UNNORMALIZED_JET_SHAPE,
        NORMALIZED_JET_SHAPE,
        UNNORMALIZED_EVENT_SHAPE,
        NORMALIZED_EVENT_SHAPE
    }

    protected TauMode tauMode = TauMode.UNDEFINED_SHAPE;
    protected double[] jetPiecesNumerator = new double[0];
    protected double beamPieceNumerator;
    protected double denominator;
    protected double[] jetPieces = new double[0];
    protected double beamPiece;
    protected double numerator;
    protected double tau;
    protected PseudoJet totalJet = new PseudoJet();
    protected List<PseudoJet> jets = new ArrayList<>();
    protected List<PseudoJet> axes = new ArrayList<>();

    /** Empty, as the C++'s default constructor. */
    public TauComponents() {
    }

    /** A copy. */
    protected TauComponents(TauComponents o) {
        tauMode = o.tauMode;
        jetPiecesNumerator = o.jetPiecesNumerator;
        beamPieceNumerator = o.beamPieceNumerator;
        denominator = o.denominator;
        jetPieces = o.jetPieces;
        beamPiece = o.beamPiece;
        numerator = o.numerator;
        tau = o.tau;
        totalJet = o.totalJet;
        jets = o.jets;
        axes = o.axes;
    }

    public TauComponents(TauMode tauMode, double[] jetPiecesNumerator, double beamPieceNumerator, double denominator,
                         List<PseudoJet> jetsIn, List<PseudoJet> axesIn) {
        this(tauMode, jetPiecesNumerator, beamPieceNumerator, denominator, jetsIn, axesIn, null);
    }

    /**
     * @param exactNumerator the sum of the pieces to 106 bits, or null to add
     *                       them in double as the C++ does
     */
    TauComponents(TauMode tauMode, double[] jetPiecesNumerator, double beamPieceNumerator, double denominator,
                  List<PseudoJet> jetsIn, List<PseudoJet> axesIn, com.sphere.core.fastjet.DD exactNumerator) {
        this.tauMode = tauMode;
        this.jetPiecesNumerator = jetPiecesNumerator.clone();
        this.beamPieceNumerator = beamPieceNumerator;
        this.denominator = denominator;
        this.jets = new ArrayList<>(jetsIn.size());
        for (PseudoJet j : jetsIn) jets.add(j.copy());
        this.axes = new ArrayList<>(axesIn);
        if (!hasDenominator() && denominator != 1.0) throw new IllegalStateException("no denominator expected");
        if (!hasBeam() && beamPieceNumerator != 0.0) throw new IllegalStateException("no beam expected");

        numerator = beamPieceNumerator;
        jetPieces = new double[jetPiecesNumerator.length];
        for (int j = 0; j < jetPiecesNumerator.length; j++) {
            jetPieces[j] = jetPiecesNumerator[j] / denominator;
            numerator += jetPiecesNumerator[j];
            final StructureType structure = new StructureType(jets.get(j));
            structure.tauPiece = jetPieces[j];
            jets.get(j).setStructure(structure);
        }
        if (exactNumerator != null) numerator = exactNumerator.doubleValue();
        beamPiece = beamPieceNumerator / denominator;
        tau = exactNumerator != null ? exactNumerator.div(denominator).doubleValue() : numerator / denominator;
        totalJet = PseudoJet.join(jets);
        final StructureType total = new StructureType(totalJet);
        total.tauPiece = tau;
        totalJet.setStructure(total);
    }

    public boolean hasDenominator() {
        return tauMode == TauMode.NORMALIZED_JET_SHAPE || tauMode == TauMode.NORMALIZED_EVENT_SHAPE;
    }

    public boolean hasBeam() {
        return tauMode == TauMode.UNNORMALIZED_EVENT_SHAPE || tauMode == TauMode.NORMALIZED_EVENT_SHAPE;
    }

    public double tau() { return tau; }
    public double[] jetPieces() { return jetPieces.clone(); }
    public double beamPiece() { return beamPiece; }
    public double[] jetPiecesNumerator() { return jetPiecesNumerator.clone(); }
    public double beamPieceNumerator() { return beamPieceNumerator; }
    public double numerator() { return numerator; }
    public double denominator() { return denominator; }
    public PseudoJet totalJet() { return totalJet.copy(); }
    public List<PseudoJet> jets() { return Collections.unmodifiableList(jets); }
    public List<PseudoJet> axes() { return Collections.unmodifiableList(axes); }

    /** The wrapped structure a jet region carries, with its piece of tau. */
    public static final class StructureType extends WrappedStructure {
        double tauPiece;

        public StructureType(PseudoJet j) {
            super(j.structure());
        }

        public double tauPiece() { return tauPiece; }
        public double tau() { return tauPiece; }
    }
}
