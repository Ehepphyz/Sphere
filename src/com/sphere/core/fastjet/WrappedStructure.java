package com.sphere.core.fastjet;

import java.util.List;

/**
 * A structure that answers by asking another, fastjet::WrappedStructure. A
 * tool that returns a jet of a clustering with a few facts of its own (the
 * mass-drop values, the pruning cuts) wraps the clustering's structure in one.
 */
public class WrappedStructure implements PseudoJetStructure {

    protected final PseudoJetStructure structure;

    public WrappedStructure(PseudoJetStructure toBeShared) {
        if (toBeShared == null) {
            throw new FastJetException("Trying to construct a wrapped structure around an empty (NULL) structure");
        }
        this.structure = toBeShared;
    }

    public PseudoJetStructure wrapped() {
        return structure;
    }

    @Override public String description() { return "PseudoJet wrapping the structure (" + structure.description() + ")"; }
    @Override public boolean hasAssociatedClusterSequence() { return structure.hasAssociatedClusterSequence(); }
    @Override public ClusterSequence associatedClusterSequence() { return structure.associatedClusterSequence(); }
    @Override public boolean hasValidClusterSequence() { return structure.hasValidClusterSequence(); }
    @Override public ClusterSequence validatedCs() { return structure.validatedCs(); }
    @Override public ClusterSequenceAreaBase validatedCsab() { return structure.validatedCsab(); }
    @Override public PseudoJet partner(PseudoJet r) { return structure.partner(r); }
    @Override public PseudoJet child(PseudoJet r) { return structure.child(r); }
    @Override public PseudoJet[] parents(PseudoJet r) { return structure.parents(r); }
    @Override public boolean objectInJet(PseudoJet r, PseudoJet jet) { return structure.objectInJet(r, jet); }
    @Override public boolean hasConstituents() { return structure.hasConstituents(); }
    @Override public List<PseudoJet> constituents(PseudoJet r) { return structure.constituents(r); }
    @Override public boolean hasExclusiveSubjets() { return structure.hasExclusiveSubjets(); }
    @Override public List<PseudoJet> exclusiveSubjets(PseudoJet r, double dcut) { return structure.exclusiveSubjets(r, dcut); }
    @Override public int nExclusiveSubjets(PseudoJet r, double dcut) { return structure.nExclusiveSubjets(r, dcut); }
    @Override public List<PseudoJet> exclusiveSubjetsUpTo(PseudoJet r, int n) { return structure.exclusiveSubjetsUpTo(r, n); }
    @Override public double exclusiveSubdmerge(PseudoJet r, int n) { return structure.exclusiveSubdmerge(r, n); }
    @Override public double exclusiveSubdmergeMax(PseudoJet r, int n) { return structure.exclusiveSubdmergeMax(r, n); }
    @Override public boolean hasPieces(PseudoJet r) { return structure.hasPieces(r); }
    @Override public List<PseudoJet> pieces(PseudoJet r) { return structure.pieces(r); }
    @Override public boolean hasArea() { return structure.hasArea(); }
    @Override public double area(PseudoJet r) { return structure.area(r); }
    @Override public double areaError(PseudoJet r) { return structure.areaError(r); }
    @Override public PseudoJet area4vector(PseudoJet r) { return structure.area4vector(r); }
    @Override public boolean isPureGhost(PseudoJet r) { return structure.isPureGhost(r); }
}
