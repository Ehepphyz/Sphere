package com.sphere.core.fastjet;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * Nearest-neighbour heuristics, fastjet::NNH: the closest pair of a set of
 * brief jets under any distance, in N^2 time for the first search and N per
 * merging on average after that (Anderberg's algorithm).
 *
 * A plugin builds one from cs.jets() and a factory of brief jets, asks for
 * {@link #dijMin} at each step, and tells it of each merging or beam
 * recombination, giving the jet indices ClusterSequence uses. The
 * bookkeeping is FastJet's slot for slot, so ties are broken the same way.
 * Brief jets computing in double-double give the low word of their distances
 * through {@link NNBriefJetBase#lowWord()}, and every comparison is then made
 * to 106 bits.
 *
 * @param <B> the brief jet
 */
public final class NNH<B extends NNBriefJet<B>> {

    private final Function<? super PseudoJet, ? extends B> factory;
    private Object[] bj;
    private double[] nnH;
    private double[] nnL;
    private int[] nn;
    private int[] index;
    private int[] whereIs;
    private int n;
    private int tail;
    private double lastLow;

    /**
     * @param jets    the jets, of which jet i gets the index i
     * @param factory makes the brief jet of a PseudoJet (the "info" of
     *                FastJet's NNH is whatever the factory captures)
     */
    public NNH(List<PseudoJet> jets, Function<? super PseudoJet, ? extends B> factory) {
        this.factory = factory;
        start(jets);
    }

    /** Starts over on a new set of jets. */
    public void start(List<PseudoJet> jets) {
        n = jets.size();
        bj = new Object[n];
        nnH = new double[n];
        nnL = new double[n];
        nn = new int[n];
        index = new int[n];
        whereIs = new int[Math.max(2 * n, 1)];
        for (int i = 0; i < n; i++) {
            initJet(i, jets.get(i), i);
            whereIs[i] = i;
        }
        tail = n;
        for (int a = 1; a < tail; a++) {
            setNNCrosscheck(a, 0, a);
        }
    }

    /**
     * The smallest distance: ab[0] gets the index of the jet with it and
     * ab[1] that of its partner, or -1 when the distance is to the beam.
     */
    public double dijMin(int[] ab) {
        double minH = nnH[0];
        double minL = nnL[0];
        int best = 0;
        for (int i = 1; i < n; i++) {
            if (lt(nnH[i], nnL[i], minH, minL)) {
                best = i;
                minH = nnH[i];
                minL = nnL[i];
            }
        }
        ab[0] = index[best];
        ab[1] = nn[best] >= 0 ? index[nn[best]] : -1;
        lastLow = minL;
        return minH;
    }

    /** The smallest distance to 106 bits (its low word is 0 for double brief jets). */
    public DD dijMinDD(int[] ab) {
        final double h = dijMin(ab);
        return new DD(h, lastLow);
    }

    /** The low word of the value the last {@link #dijMin} returned. */
    public double lastDijMinLow() {
        return lastLow;
    }

    /** Removes the jet of index iA. */
    public void removeJet(int iA) {
        final int a = whereIs[iA];
        tail--;
        n--;
        copySlot(tail, a);
        whereIs[index[a]] = a;
        for (int i = 0; i < tail; i++) {
            if (nn[i] == a) setNNNoCross(i, 0, tail);
            if (nn[i] == tail) nn[i] = a;
        }
    }

    /** Merges the jets iA and iB into the jet of index jetIndex. */
    public void mergeJets(int iA, int iB, PseudoJet jet, int jetIndex) {
        int a = whereIs[iA];
        int b = whereIs[iB];
        if (a < b) {
            final int t = a;
            a = b;
            b = t;
        }
        initJet(b, jet, jetIndex);
        if (jetIndex >= whereIs.length) whereIs = Arrays.copyOf(whereIs, Math.max(2 * jetIndex, jetIndex + 1));
        whereIs[index[b]] = b;
        tail--;
        n--;
        copySlot(tail, a);
        whereIs[index[a]] = a;
        final B jetB = jet(b);
        for (int i = 0; i < tail; i++) {
            if (nn[i] == a || nn[i] == b) {
                setNNNoCross(i, 0, tail);
            }
            final double dH = jet(i).distance(jetB);
            final double dL = jet(i).lowWord();
            if (lt(dH, dL, nnH[i], nnL[i]) && i != b) {
                nnH[i] = dH;
                nnL[i] = dL;
                nn[i] = b;
            }
            if (lt(dH, dL, nnH[b], nnL[b]) && i != b) {
                nnH[b] = dH;
                nnL[b] = dL;
                nn[b] = i;
            }
            if (nn[i] == tail) nn[i] = a;
        }
    }

    @SuppressWarnings("unchecked")
    private B jet(int slot) {
        return (B) bj[slot];
    }

    private void initJet(int slot, PseudoJet jet, int jetIndex) {
        final B b = factory.apply(jet);
        bj[slot] = b;
        index[slot] = jetIndex;
        nnH[slot] = b.beamDistance();
        nnL[slot] = b.lowWord();
        nn[slot] = -1;
    }

    private void copySlot(int from, int to) {
        bj[to] = bj[from];
        nnH[to] = nnH[from];
        nnL[to] = nnL[from];
        nn[to] = nn[from];
        index[to] = index[from];
    }

    private void setNNCrosscheck(int jet, int begin, int end) {
        final B j = jet(jet);
        double bestH = j.beamDistance();
        double bestL = j.lowWord();
        int best = -1;
        for (int b = begin; b < end; b++) {
            final double dH = j.distance(jet(b));
            final double dL = j.lowWord();
            if (lt(dH, dL, bestH, bestL)) {
                bestH = dH;
                bestL = dL;
                best = b;
            }
            if (lt(dH, dL, nnH[b], nnL[b])) {
                nnH[b] = dH;
                nnL[b] = dL;
                nn[b] = jet;
            }
        }
        nn[jet] = best;
        nnH[jet] = bestH;
        nnL[jet] = bestL;
    }

    private void setNNNoCross(int jet, int begin, int end) {
        final B j = jet(jet);
        double bestH = j.beamDistance();
        double bestL = j.lowWord();
        int best = -1;
        for (int b = begin; b < end; b++) {
            if (b == jet) continue;
            final double dH = j.distance(jet(b));
            final double dL = j.lowWord();
            if (lt(dH, dL, bestH, bestL)) {
                bestH = dH;
                bestL = dL;
                best = b;
            }
        }
        nn[jet] = best;
        nnH[jet] = bestH;
        nnL[jet] = bestL;
    }

    static boolean lt(double ah, double al, double bh, double bl) {
        return ah < bh || (ah == bh && al < bl);
    }
}
