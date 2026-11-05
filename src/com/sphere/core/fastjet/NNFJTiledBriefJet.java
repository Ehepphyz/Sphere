package com.sphere.core.fastjet;

/**
 * The brief jet of {@link NNFJN2Tiled}: an {@link NNFJBriefJet} that also
 * says where it lies in the (rapidity, phi) plane, to be put in a tile. The
 * geometrical distance must then vanish no faster than the (y, phi) one, so
 * that neighbors are found among the neighboring tiles.
 *
 * @param <B> the implementing class itself
 */
public interface NNFJTiledBriefJet<B extends NNFJTiledBriefJet<B>> extends NNFJBriefJet<B> {

    double rap();

    /** In [0, 2pi). */
    double phi();
}
