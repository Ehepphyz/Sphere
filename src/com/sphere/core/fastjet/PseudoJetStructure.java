package com.sphere.core.fastjet;

import java.util.List;

/**
 * What a jet knows beyond its momentum: where it came from, what it is made
 * of, its area. The counterpart of fastjet::PseudoJetStructureBase.
 *
 * Every method takes the jet it is asked about, since one structure is shared
 * by all the jets of a clustering. The defaults refuse, as in FastJet, so a
 * structure only implements what it can answer.
 *
 * Where the C++ fills output arguments and returns a flag, these return the
 * answer or null: {@link #parents} gives the two parents ordered by
 * decreasing pt, or null for an initial particle.
 */
public interface PseudoJetStructure {

    default String description() {
        return "PseudoJet structure";
    }

    /* ---- the clustering behind the jet ---- */

    default boolean hasAssociatedClusterSequence() {
        return false;
    }

    default ClusterSequence associatedClusterSequence() {
        return null;
    }

    default boolean hasValidClusterSequence() {
        return false;
    }

    default ClusterSequence validatedCs() {
        throw new FastJetException("This PseudoJet structure is not associated with a valid ClusterSequence");
    }

    default ClusterSequenceAreaBase validatedCsab() {
        throw new FastJetException("This PseudoJet structure is not associated with a valid cluster sequence with area");
    }

    /* ---- the history ---- */

    default PseudoJet partner(PseudoJet reference) {
        throw new FastJetException("This PseudoJet structure has no implementation for has_partner");
    }

    default PseudoJet child(PseudoJet reference) {
        throw new FastJetException("This PseudoJet structure has no implementation for has_child");
    }

    default PseudoJet[] parents(PseudoJet reference) {
        throw new FastJetException("This PseudoJet structure has no implementation for has_parents");
    }

    default boolean objectInJet(PseudoJet reference, PseudoJet jet) {
        throw new FastJetException("This PseudoJet structure has no implementation for is_inside");
    }

    /* ---- the contents ---- */

    default boolean hasConstituents() {
        return false;
    }

    default List<PseudoJet> constituents(PseudoJet reference) {
        throw new FastJetException("This PseudoJet structure has no implementation for constituents");
    }

    default boolean hasExclusiveSubjets() {
        return false;
    }

    default List<PseudoJet> exclusiveSubjets(PseudoJet reference, double dcut) {
        throw new FastJetException("This PseudoJet structure has no implementation for exclusive_subjets");
    }

    default int nExclusiveSubjets(PseudoJet reference, double dcut) {
        throw new FastJetException("This PseudoJet structure has no implementation for n_exclusive_subjets");
    }

    default List<PseudoJet> exclusiveSubjetsUpTo(PseudoJet reference, int nsub) {
        throw new FastJetException("This PseudoJet structure has no implementation for exclusive_subjets");
    }

    default double exclusiveSubdmerge(PseudoJet reference, int nsub) {
        throw new FastJetException("This PseudoJet structure has no implementation for exclusive_submerge");
    }

    default double exclusiveSubdmergeMax(PseudoJet reference, int nsub) {
        throw new FastJetException("This PseudoJet structure has no implementation for exclusive_submerge_max");
    }

    default boolean hasPieces(PseudoJet reference) {
        return false;
    }

    default List<PseudoJet> pieces(PseudoJet reference) {
        throw new FastJetException("This PseudoJet structure has no implementation for pieces");
    }

    /* ---- the area ---- */

    default boolean hasArea() {
        return false;
    }

    default double area(PseudoJet reference) {
        throw new FastJetException("This PseudoJet structure has no implementation for area");
    }

    default double areaError(PseudoJet reference) {
        throw new FastJetException("This PseudoJet structure has no implementation for area_error");
    }

    default PseudoJet area4vector(PseudoJet reference) {
        throw new FastJetException("This PseudoJet structure has no implementation for area_4vector");
    }

    default boolean isPureGhost(PseudoJet reference) {
        throw new FastJetException("This PseudoJet structure has no implementation for is_pure_ghost");
    }
}
