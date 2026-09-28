package com.sphere.core.fastjet;

/**
 * What the brief jets of the generic nearest-neighbour helpers ({@link NNH},
 * {@link NNFJN2Plain}, {@link NNFJN2Tiled}) have in common.
 *
 * A brief jet working in plain double has nothing to add. One working in
 * double-double returns the high word of each distance and gives the low
 * word here, so that the helpers compare and multiply to 106 bits.
 */
public interface NNBriefJetBase {

    /**
     * The low-order word of the value the last distance method called on
     * this jet returned; 0 for a jet computing in double.
     */
    default double lowWord() {
        return 0.0;
    }
}
