package com.sphere.core.fastjet;

/**
 * How two momenta become one, fastjet::JetDefinition::Recombiner.
 *
 * An implementation writes the result into {@code pab}; it should use the
 * double-double arithmetic when either input carries it
 * ({@link PseudoJet#precision()}), as the default one does.
 */
public interface Recombiner {

    String description();

    /** Sets pab to the recombination of pa and pb. */
    void recombine(PseudoJet pa, PseudoJet pb, PseudoJet pab);

    /** Prepares an input particle, before any clustering. */
    default void preprocess(PseudoJet p) {
    }

    /** pa becomes the recombination of pa and pb. */
    default void plusEqual(PseudoJet pa, PseudoJet pb) {
        PseudoJet pres = new PseudoJet(0, 0, 0, 0, (pa.dd || pb.dd) ? Precision.DD : Precision.DOUBLE);
        recombine(pa, pb, pres);
        pa.reset(pres);
    }

    /** The scheme this recombiner implements, EXTERNAL_SCHEME if not a default one. */
    default RecombinationScheme scheme() {
        return RecombinationScheme.EXTERNAL_SCHEME;
    }
}
