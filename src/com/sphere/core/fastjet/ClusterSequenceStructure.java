package com.sphere.core.fastjet;

import java.util.ArrayList;
import java.util.List;

/**
 * The structure every jet of a clustering shares, fastjet::ClusterSequenceStructure.
 * It answers by asking the ClusterSequence, which garbage collection keeps
 * alive for as long as a jet refers to it.
 */
public class ClusterSequenceStructure implements PseudoJetStructure {

    private ClusterSequence cs;

    public ClusterSequenceStructure(ClusterSequence cs) {
        this.cs = cs;
    }

    void setAssociatedCs(ClusterSequence cs) {
        this.cs = cs;
    }

    @Override
    public String description() {
        return "PseudoJet with an associated ClusterSequence";
    }

    @Override
    public boolean hasAssociatedClusterSequence() {
        return true;
    }

    @Override
    public ClusterSequence associatedClusterSequence() {
        return cs;
    }

    @Override
    public boolean hasValidClusterSequence() {
        return cs != null;
    }

    @Override
    public ClusterSequence validatedCs() {
        if (cs == null) {
            throw new FastJetException("you requested information about the internal structure of a jet, but its associated ClusterSequence has gone out of scope.");
        }
        return cs;
    }

    @Override
    public ClusterSequenceAreaBase validatedCsab() {
        if (!(validatedCs() instanceof ClusterSequenceAreaBase csab)) {
            throw new FastJetException("you requested jet-area related information, but the PseudoJet does not have associated area information.");
        }
        return csab;
    }

    @Override
    public PseudoJet partner(PseudoJet reference) {
        return validatedCs().partner(reference);
    }

    @Override
    public PseudoJet child(PseudoJet reference) {
        return validatedCs().child(reference);
    }

    @Override
    public PseudoJet[] parents(PseudoJet reference) {
        return validatedCs().parents(reference);
    }

    @Override
    public boolean objectInJet(PseudoJet reference, PseudoJet jet) {
        if (!jet.hasAssociatedClusterSequence()) {
            throw new FastJetException("you requested information about the internal structure of a jet, but it is not associated with a ClusterSequence or its associated ClusterSequence has gone out of scope.");
        }
        if (reference.associatedClusterSequence() != jet.associatedClusterSequence()) {
            return false;
        }
        return validatedCs().objectInJet(reference, jet);
    }

    @Override
    public boolean hasConstituents() {
        return true;
    }

    @Override
    public List<PseudoJet> constituents(PseudoJet reference) {
        return validatedCs().constituents(reference);
    }

    @Override
    public boolean hasExclusiveSubjets() {
        return true;
    }

    @Override
    public List<PseudoJet> exclusiveSubjets(PseudoJet reference, double dcut) {
        return validatedCs().exclusiveSubjets(reference, dcut);
    }

    @Override
    public int nExclusiveSubjets(PseudoJet reference, double dcut) {
        return validatedCs().nExclusiveSubjets(reference, dcut);
    }

    @Override
    public List<PseudoJet> exclusiveSubjetsUpTo(PseudoJet reference, int nsub) {
        return validatedCs().exclusiveSubjetsUpTo(reference, nsub);
    }

    @Override
    public double exclusiveSubdmerge(PseudoJet reference, int nsub) {
        return validatedCs().exclusiveSubdmerge(reference, nsub);
    }

    @Override
    public double exclusiveSubdmergeMax(PseudoJet reference, int nsub) {
        return validatedCs().exclusiveSubdmergeMax(reference, nsub);
    }

    @Override
    public boolean hasPieces(PseudoJet reference) {
        return parents(reference) != null;
    }

    @Override
    public List<PseudoJet> pieces(PseudoJet reference) {
        final PseudoJet[] p = parents(reference);
        final List<PseudoJet> res = new ArrayList<>(2);
        if (p != null) {
            res.add(p[0]);
            res.add(p[1]);
        }
        return res;
    }

    @Override
    public boolean hasArea() {
        return cs instanceof ClusterSequenceAreaBase;
    }

    @Override
    public double area(PseudoJet reference) {
        return validatedCsab().area(reference);
    }

    @Override
    public double areaError(PseudoJet reference) {
        return validatedCsab().areaError(reference);
    }

    @Override
    public PseudoJet area4vector(PseudoJet reference) {
        return validatedCsab().area4vector(reference);
    }

    @Override
    public boolean isPureGhost(PseudoJet reference) {
        return validatedCsab().isPureGhost(reference);
    }
}
