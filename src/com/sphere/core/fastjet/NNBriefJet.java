package com.sphere.core.fastjet;

/**
 * The brief jet of {@link NNH}, the BJ template parameter of FastJet's NNH:
 * a jet-jet distance and a jet-beam distance.
 *
 * @param <B> the implementing class itself
 */
public interface NNBriefJet<B extends NNBriefJet<B>> extends NNBriefJetBase {

    /** The distance to another jet (its high word under double-double). */
    double distance(B other);

    /** The distance to the beam (its high word under double-double). */
    double beamDistance();
}
