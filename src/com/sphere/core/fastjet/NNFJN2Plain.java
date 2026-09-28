package com.sphere.core.fastjet;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * fastjet::NNFJN2Plain: the closest pair under a distance of FastJet's
 * factorised form, d_ij = min(m_i, m_j) g_ij, d_iB = m_i g_iB, which lets the
 * nearest neighbours be found on g alone, as in the N2Plain strategy of the
 * kt-type algorithms. Used like {@link NNH}.
 *
 * Under {@link Precision#DD} the d_iJ = g m products are made to 106 bits;
 * under {@link Precision#DOUBLE} they are the plain double products FastJet
 * makes, so that results are bit for bit its own.
 *
 * @param <B> the brief jet
 */
public final class NNFJN2Plain<B extends NNFJBriefJet<B>> {

    private final Function<? super PseudoJet, ? extends B> factory;
    private final boolean dd;
    private Object[] bj;
    private double[] nnH;
    private double[] nnL;
    private int[] nn;
    private int[] index;
    private double[] diJH;
    private double[] diJL;
    private int[] whereIs;
    private int n;
    private int tail;
    private double lastLow;
    private double prodL;

    public NNFJN2Plain(List<PseudoJet> jets, Function<? super PseudoJet, ? extends B> factory) {
        this(jets, factory, Precision.DOUBLE);
    }

    /** @param precision whether the d_iJ products are made to 106 bits */
    public NNFJN2Plain(List<PseudoJet> jets, Function<? super PseudoJet, ? extends B> factory, Precision precision) {
        this.factory = factory;
        this.dd = precision == Precision.DD;
        start(jets);
    }

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
        diJH = new double[n];
        diJL = new double[n];
        for (int i = 0; i < n; i++) {
            computeDiJ(i);
        }
    }

    /** See {@link NNH#dijMin}. */
    public double dijMin(int[] ab) {
        double minH = diJH[0];
        double minL = diJL[0];
        int best = 0;
        for (int i = 1; i < n; i++) {
            if (NNH.lt(diJH[i], diJL[i], minH, minL)) {
                best = i;
                minH = diJH[i];
                minL = diJL[i];
            }
        }
        ab[0] = index[best];
        ab[1] = nn[best] >= 0 ? index[nn[best]] : -1;
        lastLow = minL;
        return minH;
    }

    public DD dijMinDD(int[] ab) {
        final double h = dijMin(ab);
        return new DD(h, lastLow);
    }

    public double lastDijMinLow() {
        return lastLow;
    }

    public void removeJet(int iA) {
        final int a = whereIs[iA];
        tail--;
        n--;
        copySlot(tail, a);
        whereIs[index[a]] = a;
        diJH[a] = diJH[tail];
        diJL[a] = diJL[tail];
        for (int i = 0; i < tail; i++) {
            if (nn[i] == a) {
                setNNNoCross(i, 0, tail);
                computeDiJ(i);
            }
            if (nn[i] == tail) nn[i] = a;
        }
    }

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
        diJH[a] = diJH[tail];
        diJL[a] = diJL[tail];
        final B jetB = jet(b);
        for (int i = 0; i < tail; i++) {
            if (nn[i] == a || nn[i] == b) {
                setNNNoCross(i, 0, tail);
                computeDiJ(i);
            }
            final double dH = jet(i).geometricalDistance(jetB);
            final double dL = jet(i).lowWord();
            if (NNH.lt(dH, dL, nnH[i], nnL[i]) && i != b) {
                nnH[i] = dH;
                nnL[i] = dL;
                nn[i] = b;
                computeDiJ(i);
            }
            if (NNH.lt(dH, dL, nnH[b], nnL[b]) && i != b) {
                nnH[b] = dH;
                nnL[b] = dL;
                nn[b] = i;
            }
            if (nn[i] == tail) nn[i] = a;
        }
        computeDiJ(b);
    }

    @SuppressWarnings("unchecked")
    private B jet(int slot) {
        return (B) bj[slot];
    }

    /** d_iJ of a slot: its NN distance times the smaller momentum factor. */
    private void computeDiJ(int s) {
        final B j = jet(s);
        double mH = j.momentumFactor();
        double mL = j.momentumFactorLow();
        if (nn[s] >= 0) {
            final B o = jet(nn[s]);
            final double oH = o.momentumFactor();
            final double oL = o.momentumFactorLow();
            if (NNH.lt(oH, oL, mH, mL)) {
                mH = oH;
                mL = oL;
            }
        }
        diJH[s] = mul(nnH[s], nnL[s], mH, mL);
        diJL[s] = prodL;
    }

    /** a*b, as FastJet in double; to 106 bits, the low word in prodL, in DD. */
    private double mul(double ah, double al, double bh, double bl) {
        if (!dd) {
            prodL = 0.0;
            return ah * bh;
        }
        final double p = ah * bh;
        if (Double.isInfinite(p) || p == 0.0 || Double.isNaN(p)) {
            prodL = 0.0;
            return p;
        }
        final double e = DD.twoProdErr(ah, bh, p) + (ah * bl + al * bh);
        final double h = p + e;
        prodL = e - (h - p);
        return h;
    }

    private void initJet(int slot, PseudoJet jet, int jetIndex) {
        final B b = factory.apply(jet);
        bj[slot] = b;
        index[slot] = jetIndex;
        nnH[slot] = b.geometricalBeamDistance();
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
        double bestH = j.geometricalBeamDistance();
        double bestL = j.lowWord();
        int best = -1;
        for (int b = begin; b < end; b++) {
            final double dH = j.geometricalDistance(jet(b));
            final double dL = j.lowWord();
            if (NNH.lt(dH, dL, bestH, bestL)) {
                bestH = dH;
                bestL = dL;
                best = b;
            }
            if (NNH.lt(dH, dL, nnH[b], nnL[b])) {
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
        double bestH = j.geometricalBeamDistance();
        double bestL = j.lowWord();
        int best = -1;
        for (int b = begin; b < end; b++) {
            if (b == jet) continue;
            final double dH = j.geometricalDistance(jet(b));
            final double dL = j.lowWord();
            if (NNH.lt(dH, dL, bestH, bestL)) {
                bestH = dH;
                bestL = dL;
                best = b;
            }
        }
        nn[jet] = best;
        nnH[jet] = bestH;
        nnL[jet] = bestL;
    }
}
