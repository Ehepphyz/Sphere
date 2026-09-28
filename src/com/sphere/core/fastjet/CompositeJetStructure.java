package com.sphere.core.fastjet;

import java.util.ArrayList;
import java.util.List;

/**
 * The structure of a jet built by joining pieces, fastjet::CompositeJetStructure.
 *
 * The constituents are those of every piece, and the area, when all pieces
 * have one, is the sum of theirs.
 */
public class CompositeJetStructure implements PseudoJetStructure {

    protected final List<PseudoJet> pieces;
    protected PseudoJet area4vector;

    public CompositeJetStructure(List<PseudoJet> initialPieces, Recombiner recombiner) {
        pieces = new ArrayList<>(initialPieces.size());
        for (PseudoJet p : initialPieces) {
            pieces.add(p.copy());
        }
        boolean hasAreaLocal = true;
        for (PseudoJet p : pieces) {
            if (!p.hasArea()) {
                hasAreaLocal = false;
            }
        }
        if (hasAreaLocal) {
            area4vector = new PseudoJet();
            for (PseudoJet p : pieces) {
                if (recombiner != null) {
                    recombiner.plusEqual(area4vector, p.area4vector());
                } else {
                    area4vector.plusEqual(p.area4vector());
                }
            }
        }
    }

    @Override
    public String description() {
        return "Composite PseudoJet";
    }

    @Override
    public boolean hasConstituents() {
        return !pieces.isEmpty();
    }

    @Override
    public List<PseudoJet> constituents(PseudoJet jet) {
        List<PseudoJet> all = new ArrayList<>();
        for (PseudoJet p : pieces) {
            if (p.hasConstituents()) {
                all.addAll(p.constituents());
            } else {
                all.add(p.copy());
            }
        }
        return all;
    }

    @Override
    public boolean hasPieces(PseudoJet jet) {
        return true;
    }

    @Override
    public List<PseudoJet> pieces(PseudoJet jet) {
        List<PseudoJet> out = new ArrayList<>(pieces.size());
        for (PseudoJet p : pieces) {
            out.add(p.copy());
        }
        return out;
    }

    @Override
    public boolean hasArea() {
        return area4vector != null;
    }

    @Override
    public double area(PseudoJet reference) {
        if (!hasArea()) {
            throw new FastJetException("One or more of this composite jet's pieces does not support area");
        }
        double a = 0;
        for (PseudoJet p : pieces) {
            a += p.area();
        }
        return a;
    }

    @Override
    public double areaError(PseudoJet reference) {
        if (!hasArea()) {
            throw new FastJetException("One or more of this composite jet's pieces does not support area");
        }
        double a = 0;
        for (PseudoJet p : pieces) {
            a += p.areaError();
        }
        return a;
    }

    @Override
    public PseudoJet area4vector(PseudoJet reference) {
        if (!hasArea()) {
            throw new FastJetException("One or more of this composite jet's pieces does not support area");
        }
        return area4vector.copy();
    }

    @Override
    public boolean isPureGhost(PseudoJet reference) {
        for (PseudoJet p : pieces) {
            if (!p.isPureGhost()) {
                return false;
            }
        }
        return true;
    }

    /** Drops the area, as a filter does when it cannot vouch for it. */
    public void discardArea() {
        area4vector = null;
    }
}
