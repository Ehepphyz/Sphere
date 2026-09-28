package com.sphere.core.fastjet;

/**
 * The brief jet of {@link NNFJN2Plain}: FastJet's "FJ" factorisation of the
 * distance, d_ij = min(m_i, m_j) g_ij and d_iB = m_i g_iB, where only the
 * geometrical part g decides who is whose nearest neighbour.
 *
 * @param <B> the implementing class itself
 */
public interface NNFJBriefJet<B extends NNFJBriefJet<B>> extends NNBriefJetBase {

    /** The geometrical distance g_ij (its high word under double-double). */
    double geometricalDistance(B other);

    /** The geometrical beam distance g_iB (its high word under double-double). */
    double geometricalBeamDistance();

    /** The momentum factor m_i (its high word under double-double). */
    double momentumFactor();

    /** The low word of {@link #momentumFactor()}; 0 in double. */
    default double momentumFactorLow() {
        return 0.0;
    }
}
