package com.sphere.core.fastjet;

import java.util.ArrayList;

/**
 * What every sequential-recombination strategy shares: the "brief jets" of
 * FastJet (rapidity, azimuth, the algorithm's kt^2p, the nearest neighbour and
 * its distance) kept as parallel arrays, and the distances between them.
 *
 * Every quantity is a pair (high, low). Under {@link Precision#DOUBLE} the
 * low parts stay zero and the arithmetic is exactly the C++ one; under
 * {@link Precision#DD} each distance is computed to 106 bits. The comparisons
 * are written once for pairs, and reduce to the double ones when the low parts
 * are zero, which keeps a single implementation of each algorithm.
 */
abstract class BriefJetEngine {

    static final double PI = Math.PI;
    static final double TWOPI = 2.0 * Math.PI;
    static final double PI_H = DD.PI.hi, PI_L = DD.PI.lo;
    static final double TWOPI_H = DD.TWO_PI.hi, TWOPI_L = DD.TWO_PI.lo;

    final ClusterSequence cs;
    final ArrayList<PseudoJet> jets;
    final boolean dd;
    final double r2H, r2L, invR2H, invR2L;

    double[] etaH, etaL, phiH, phiL, kt2H, kt2L, nnH, nnL;
    int[] nn, jetsIndex;

    /** The last distance computed. */
    double dH, dL;
    /** The last diJ computed. */
    double rH, rL;

    BriefJetEngine(ClusterSequence cs) {
        this.cs = cs;
        this.jets = cs.internalJets();
        this.dd = cs.dd;
        this.r2H = cs.r2;
        this.r2L = cs.r2L;
        this.invR2H = cs.invR2;
        this.invR2L = cs.invR2L;
    }

    void allocate(int n) {
        etaH = new double[n];
        etaL = new double[n];
        phiH = new double[n];
        phiL = new double[n];
        kt2H = new double[n];
        kt2L = new double[n];
        nnH = new double[n];
        nnL = new double[n];
        nn = new int[n];
        jetsIndex = new int[n];
    }

    /** Fills a slot from _jets[jetIndex], as _bj_set_jetinfo does. */
    void setJetInfo(int s, int jetIndex) {
        final PseudoJet j = jets.get(jetIndex);
        if (dd) {
            final DD y = j.rapDD();
            final DD f = j.phiDD();
            final DD k = cs.jetScaleForAlgorithmDD(j);
            etaH[s] = y.hi;
            etaL[s] = y.lo;
            phiH[s] = f.hi;
            phiL[s] = f.lo;
            kt2H[s] = k.hi;
            kt2L[s] = k.lo;
        } else {
            etaH[s] = j.rap();
            phiH[s] = j.phi();
            kt2H[s] = cs.jetScaleForAlgorithm(j);
            etaL[s] = 0.0;
            phiL[s] = 0.0;
            kt2L[s] = 0.0;
        }
        jetsIndex[s] = jetIndex;
        nnH[s] = r2H;
        nnL[s] = r2L;
        nn[s] = -1;
    }

    void copySlot(int from, int to) {
        etaH[to] = etaH[from];
        etaL[to] = etaL[from];
        phiH[to] = phiH[from];
        phiL[to] = phiL[from];
        kt2H[to] = kt2H[from];
        kt2L[to] = kt2L[from];
        nnH[to] = nnH[from];
        nnL[to] = nnL[from];
        nn[to] = nn[from];
        jetsIndex[to] = jetsIndex[from];
    }

    /** The squared rapidity-azimuth distance, azimuth taken periodically. */
    final void dist(int a, int b) {
        if (!dd) {
            double dphi = Math.abs(phiH[a] - phiH[b]);
            final double deta = etaH[a] - etaH[b];
            if (dphi > PI) {
                dphi = TWOPI - dphi;
            }
            dH = dphi * dphi + deta * deta;
            dL = 0.0;
            return;
        }
        // dphi = |phi_a - phi_b|
        double s = phiH[a] - phiH[b];
        double e = DD.twoSumErr(phiH[a], -phiH[b], s) + (phiL[a] - phiL[b]);
        double ph = s + e;
        double pl = e - (ph - s);
        if (ph < 0.0 || (ph == 0.0 && pl < 0.0)) {
            ph = -ph;
            pl = -pl;
        }
        if (ph > PI_H || (ph == PI_H && pl > PI_L)) {
            s = TWOPI_H - ph;
            e = DD.twoSumErr(TWOPI_H, -ph, s) + (TWOPI_L - pl);
            ph = s + e;
            pl = e - (ph - s);
        }
        squareSum(ph, pl, a, b);
    }

    /** The same without the periodicity, for jets known to be close in azimuth. */
    final void distNotPeriodic(int a, int b) {
        if (!dd) {
            final double dphi = phiH[a] - phiH[b];
            final double deta = etaH[a] - etaH[b];
            dH = dphi * dphi + deta * deta;
            dL = 0.0;
            return;
        }
        final double s = phiH[a] - phiH[b];
        final double e = DD.twoSumErr(phiH[a], -phiH[b], s) + (phiL[a] - phiL[b]);
        final double ph = s + e;
        final double pl = e - (ph - s);
        squareSum(ph, pl, a, b);
    }

    /** (dH, dL) = dphi^2 + (eta_a - eta_b)^2, dphi given as a pair. */
    private void squareSum(double ph, double pl, int a, int b) {
        double s = etaH[a] - etaH[b];
        double e = DD.twoSumErr(etaH[a], -etaH[b], s) + (etaL[a] - etaL[b]);
        final double eh = s + e;
        final double el = e - (eh - s);
        final double p1 = ph * ph;
        final double p1e = DD.twoProdErr(ph, ph, p1) + 2.0 * ph * pl;
        final double p2 = eh * eh;
        final double p2e = DD.twoProdErr(eh, eh, p2) + 2.0 * eh * el;
        s = p1 + p2;
        e = DD.twoSumErr(p1, p2, s) + p1e + p2e;
        dH = s + e;
        dL = e - (dH - s);
    }

    /** True when the last distance is below the slot's nearest-neighbour distance. */
    final boolean distLessThanNN(int s) {
        return dH < nnH[s] || (dH == nnH[s] && dL < nnL[s]);
    }

    final void setNNFromDist(int s, int neighbour) {
        nnH[s] = dH;
        nnL[s] = dL;
        nn[s] = neighbour;
    }

    /** diJ = NN_dist * min(kt2, kt2 of NN), into (rH, rL). */
    final void diJ(int s) {
        double kh = kt2H[s];
        double kl = kt2L[s];
        final int n = nn[s];
        if (n >= 0 && (kt2H[n] < kh || (kt2H[n] == kh && kt2L[n] < kl))) {
            kh = kt2H[n];
            kl = kt2L[n];
        }
        if (!dd) {
            rH = nnH[s] * kh;
            rL = 0.0;
            return;
        }
        mul(nnH[s], nnL[s], kh, kl);
    }

    /** (rH, rL) = a * b. */
    final void mul(double ah, double al, double bh, double bl) {
        final double p1 = ah * bh;
        final double p2 = DD.twoProdErr(ah, bh, p1) + (ah * bl + al * bh);
        rH = p1 + p2;
        rL = p2 - (rH - p1);
    }

    /** The recorded distance: diJ times 1/R^2, into (rH, rL). */
    final void normalise(double h, double l) {
        if (!dd) {
            rH = h * invR2H;
            rL = 0.0;
            return;
        }
        mul(h, l, invR2H, invR2L);
    }

    static boolean lt(double ah, double al, double bh, double bl) {
        return ah < bh || (ah == bh && al < bl);
    }

    static boolean le(double ah, double al, double bh, double bl) {
        return ah < bh || (ah == bh && al <= bl);
    }

    /* ------------------------------------------------------------------ */
    /* The min-heap of FastJet, over pairs                                 */
    /* ------------------------------------------------------------------ */

    /**
     * fastjet::MinHeap: a binary tree where each node knows the location of the
     * smallest value below it, so that the minimum is read in constant time and
     * an update costs log n.
     */
    static final class MinHeap {
        private final double[] vH;
        private final double[] vL;
        private final int[] minloc;
        private final int size;

        MinHeap(double[] valuesH, double[] valuesL, int n) {
            size = n;
            vH = new double[n];
            vL = new double[n];
            minloc = new int[n];
            for (int i = 0; i < n; i++) {
                vH[i] = valuesH[i];
                vL[i] = valuesL[i];
                minloc[i] = i;
            }
            for (int i = size - 1; i > 0; i--) {
                final int parent = (i - 1) / 2;
                if (lt(vH[minloc[i]], vL[minloc[i]], vH[minloc[parent]], vL[minloc[parent]])) {
                    minloc[parent] = minloc[i];
                }
            }
        }

        int minloc() {
            return minloc[0];
        }

        double minvalH() {
            return vH[minloc[0]];
        }

        double minvalL() {
            return vL[minloc[0]];
        }

        void remove(int loc) {
            update(loc, Double.MAX_VALUE, 0.0);
        }

        void update(int loc, double newH, double newL) {
            final int start = loc;
            if (minloc[start] != start && !lt(newH, newL, vH[minloc[start]], vL[minloc[start]])) {
                vH[start] = newH;
                vL[start] = newL;
                return;
            }
            vH[start] = newH;
            vL[start] = newL;
            minloc[start] = start;
            boolean changeMade = true;
            int l = loc;
            while (changeMade) {
                final int here = l;
                changeMade = false;
                if (minloc[here] == start) {
                    minloc[here] = here;
                    changeMade = true;
                }
                int child = 2 * l + 1;
                if (child < size && lt(vH[minloc[child]], vL[minloc[child]], vH[minloc[here]], vL[minloc[here]])) {
                    minloc[here] = minloc[child];
                    changeMade = true;
                }
                child++;
                if (child < size && lt(vH[minloc[child]], vL[minloc[child]], vH[minloc[here]], vL[minloc[here]])) {
                    minloc[here] = minloc[child];
                    changeMade = true;
                }
                if (l == 0) {
                    break;
                }
                l = (l - 1) / 2;
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* The rapidity range worth tiling, fastjet::TilingExtent              */
    /* ------------------------------------------------------------------ */

    /** {minrap, maxrap, sum of binned squared multiplicity}. */
    static double[] tilingExtent(ArrayList<PseudoJet> particles) {
        final int nrap = 20;
        final int nbins = 2 * nrap;
        final double[] counts = new double[nbins];
        double minrap = Double.MAX_VALUE;
        double maxrap = -Double.MAX_VALUE;
        int ibin;
        for (PseudoJet p : particles) {
            if (p.E() == Math.abs(p.pz())) continue;
            final double rap = p.rap();
            if (rap < minrap) minrap = rap;
            if (rap > maxrap) maxrap = rap;
            ibin = (int) (rap + nrap);
            if (ibin < 0) ibin = 0;
            if (ibin >= nbins) ibin = nbins - 1;
            counts[ibin]++;
        }
        if (minrap > maxrap) {
            minrap = -2.5;
            maxrap = 2.5;
            for (PseudoJet p : particles) {
                final double rap = p.pz() > 0 ? maxrap : minrap;
                ibin = (int) (rap + nrap);
                if (ibin < 0) ibin = 0;
                if (ibin >= nbins) ibin = nbins - 1;
                counts[ibin]++;
            }
        }
        double maxInBin = 0;
        for (ibin = 0; ibin < nbins; ibin++) {
            if (maxInBin < counts[ibin]) maxInBin = counts[ibin];
        }
        final double allowedMaxFraction = 0.25;
        final double minMultiplicity = 4;
        double allowedMaxCumul = Math.floor(Math.max(maxInBin * allowedMaxFraction, minMultiplicity));
        if (allowedMaxCumul > maxInBin) allowedMaxCumul = maxInBin;

        double cumulLo = 0;
        double cumul2 = 0;
        for (ibin = 0; ibin < nbins; ibin++) {
            cumulLo += counts[ibin];
            if (cumulLo >= allowedMaxCumul) {
                final double y = ibin - nrap;
                if (y > minrap) minrap = y;
                break;
            }
        }
        cumul2 += cumulLo * cumulLo;
        final int ibinLo = ibin;
        double cumulHi = 0;
        for (ibin = nbins - 1; ibin >= 0; ibin--) {
            cumulHi += counts[ibin];
            if (cumulHi >= allowedMaxCumul) {
                final double y = ibin - nrap + 1;
                if (y < maxrap) maxrap = y;
                break;
            }
        }
        final int ibinHi = ibin;
        if (ibinHi == ibinLo) {
            cumul2 = Math.pow(cumulLo + cumulHi - counts[ibinHi], 2);
        } else {
            cumul2 += cumulHi * cumulHi;
            for (ibin = ibinLo + 1; ibin < ibinHi; ibin++) {
                cumul2 += counts[ibin] * counts[ibin];
            }
        }
        return new double[]{minrap, maxrap, cumul2};
    }
}
