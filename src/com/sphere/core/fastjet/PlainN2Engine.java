package com.sphere.core.fastjet;

/**
 * The plain N^2 strategy, ClusterSequence::_simple_N2_cluster, for the
 * longitudinally invariant algorithms and for the e+e- ones.
 *
 * For e+e- a jet is kept as its unit direction and the distance is
 * 2(1 - cos theta). FastJet computes it as 1 - n_a.n_b, which loses all
 * relative precision below theta ~ 1e-8, or through the cross product in its
 * EEAccurate variant. Under {@link Precision#DD} it is computed here as the
 * squared chord |n_a - n_b|^2, which is the same quantity with no
 * cancellation at all, on unit vectors normalised to 106 bits.
 */
final class PlainN2Engine extends BriefJetEngine {

    private final boolean ee;
    private final boolean eeAccurate;
    private double[] nxH, nxL, nyH, nyL, nzH, nzL;

    PlainN2Engine(ClusterSequence cs, boolean ee, boolean eeAccurate) {
        super(cs);
        this.ee = ee;
        this.eeAccurate = eeAccurate;
    }

    @Override
    void allocate(int n) {
        super.allocate(n);
        if (ee) {
            nxH = new double[n];
            nxL = new double[n];
            nyH = new double[n];
            nyL = new double[n];
            nzH = new double[n];
            nzL = new double[n];
        }
    }

    @Override
    void setJetInfo(int s, int jetIndex) {
        if (!ee) {
            super.setJetInfo(s, jetIndex);
            return;
        }
        final PseudoJet j = jets.get(jetIndex);
        final double p = cs.jetDef.extraParam();
        if (dd) {
            DD scale = j.eDD().sqr();
            if (cs.jetAlgorithm == JetAlgorithm.EE_GENKT) {
                if (p <= 0 && scale.lt(1e-300)) scale = new DD(1e-300);
                scale = scale.pow(p);
            } else if (cs.jetAlgorithm != JetAlgorithm.EE_KT) {
                throw new FastJetException("Unrecognised jet algorithm");
            }
            kt2H[s] = scale.hi;
            kt2L[s] = scale.lo;
            final DD norm2 = j.kt2DD().add(j.pzDD().sqr());
            if (norm2.gt(0.0)) {
                final DD inv = DD.ONE.div(norm2.sqrt());
                final DD x = j.pxDD().mul(inv);
                final DD y = j.pyDD().mul(inv);
                final DD z = j.pzDD().mul(inv);
                nxH[s] = x.hi;
                nxL[s] = x.lo;
                nyH[s] = y.hi;
                nyL[s] = y.lo;
                nzH[s] = z.hi;
                nzL[s] = z.lo;
            } else {
                nxH[s] = 0.0;
                nxL[s] = 0.0;
                nyH[s] = 0.0;
                nyL[s] = 0.0;
                nzH[s] = 1.0;
                nzL[s] = 0.0;
            }
        } else {
            final double e = j.E();
            double scale = e * e;
            if (cs.jetAlgorithm == JetAlgorithm.EE_GENKT) {
                if (p <= 0 && scale < 1e-300) scale = 1e-300;
                scale = CRMath.pow(scale, p);
            } else if (cs.jetAlgorithm != JetAlgorithm.EE_KT) {
                throw new FastJetException("Unrecognised jet algorithm");
            }
            kt2H[s] = scale;
            kt2L[s] = 0.0;
            double norm = j.modp2();
            if (norm > 0) {
                norm = 1.0 / Math.sqrt(norm);
                nxH[s] = norm * j.px();
                nyH[s] = norm * j.py();
                nzH[s] = norm * j.pz();
            } else {
                nxH[s] = 0.0;
                nyH[s] = 0.0;
                nzH[s] = 1.0;
            }
        }
        jetsIndex[s] = jetIndex;
        nnH[s] = r2H;
        nnL[s] = r2L;
        nn[s] = -1;
    }

    @Override
    void copySlot(int from, int to) {
        super.copySlot(from, to);
        if (ee) {
            nxH[to] = nxH[from];
            nxL[to] = nxL[from];
            nyH[to] = nyH[from];
            nyL[to] = nyL[from];
            nzH[to] = nzH[from];
            nzL[to] = nzL[from];
        }
    }

    /** The distance of the kind of clustering this is. */
    private void distance(int a, int b) {
        if (!ee) {
            dist(a, b);
            return;
        }
        if (!dd) {
            double d = 1.0 - nxH[a] * nxH[b] - nyH[a] * nyH[b] - nzH[a] * nzH[b];
            if (eeAccurate && d * d < Math.ulp(1.0)) {
                final double cx = nyH[a] * nzH[b] - nyH[b] * nzH[a];
                final double cy = nzH[a] * nxH[b] - nzH[b] * nxH[a];
                final double cz = nxH[a] * nyH[b] - nxH[b] * nyH[a];
                dH = cx * cx + cy * cy + cz * cz;
                dL = 0.0;
                return;
            }
            dH = d * 2;
            dL = 0.0;
            return;
        }
        // |n_a - n_b|^2 = 2 (1 - cos theta), exactly, with no cancellation.
        double sumH = 0.0;
        double sumL = 0.0;
        for (int c = 0; c < 3; c++) {
            final double ah, al, bh, bl;
            if (c == 0) {
                ah = nxH[a]; al = nxL[a]; bh = nxH[b]; bl = nxL[b];
            } else if (c == 1) {
                ah = nyH[a]; al = nyL[a]; bh = nyH[b]; bl = nyL[b];
            } else {
                ah = nzH[a]; al = nzL[a]; bh = nzH[b]; bl = nzL[b];
            }
            final double s = ah - bh;
            final double e = DD.twoSumErr(ah, -bh, s) + (al - bl);
            final double xh = s + e;
            final double xl = e - (xh - s);
            final double p1 = xh * xh;
            final double p1e = DD.twoProdErr(xh, xh, p1) + 2.0 * xh * xl;
            final double t = sumH + p1;
            final double te = DD.twoSumErr(sumH, p1, t) + sumL + p1e;
            sumH = t + te;
            sumL = te - (sumH - t);
        }
        dH = sumH;
        dL = sumL;
    }

    private void setNNNoCross(int jet, int head, int tail) {
        double bestH = r2H;
        double bestL = r2L;
        int best = -1;
        for (int b = head; b < jet; b++) {
            distance(jet, b);
            if (lt(dH, dL, bestH, bestL)) {
                bestH = dH;
                bestL = dL;
                best = b;
            }
        }
        for (int b = jet + 1; b < tail; b++) {
            distance(jet, b);
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

    private void setNNCrosscheck(int jet, int head, int tail) {
        double bestH = r2H;
        double bestL = r2L;
        int best = -1;
        for (int b = head; b < tail; b++) {
            distance(jet, b);
            if (lt(dH, dL, bestH, bestL)) {
                bestH = dH;
                bestL = dL;
                best = b;
            }
            if (distLessThanNN(b)) {
                setNNFromDist(b, jet);
            }
        }
        nn[jet] = best;
        nnH[jet] = bestH;
        nnL[jet] = bestL;
    }

    void run() {
        int n = jets.size();
        allocate(n);
        for (int i = 0; i < n; i++) {
            setJetInfo(i, i);
        }
        int tail = n;
        final int head = 0;
        for (int a = head + 1; a != tail; a++) {
            setNNCrosscheck(a, head, a);
        }
        final double[] diJH = new double[n];
        final double[] diJL = new double[n];
        for (int i = 0; i < n; i++) {
            diJ(i);
            diJH[i] = rH;
            diJL[i] = rL;
        }

        while (tail != head) {
            double minH = diJH[0];
            double minL = diJL[0];
            int minJet = 0;
            for (int i = 1; i < n; i++) {
                if (lt(diJH[i], diJL[i], minH, minL)) {
                    minJet = i;
                    minH = diJH[i];
                    minL = diJL[i];
                }
            }
            int jetA = minJet;
            int jetB = nn[jetA];
            normalise(minH, minL);
            final double dijH = rH;
            final double dijL = rL;
            if (jetB >= 0) {
                if (jetA < jetB) {
                    final int t = jetA;
                    jetA = jetB;
                    jetB = t;
                }
                final int nnew = cs.doIJRecombinationStep(jetsIndex[jetA], jetsIndex[jetB], dijH, dijL);
                setJetInfo(jetB, nnew);
            } else {
                cs.doIBRecombinationStep(jetsIndex[jetA], dijH, dijL);
            }

            tail--;
            n--;
            copySlot(tail, jetA);
            diJH[jetA] = diJH[tail];
            diJL[jetA] = diJL[tail];

            if (jetB >= 0) {
                for (int jetI = head; jetI != tail; jetI++) {
                    if (nn[jetI] == jetA || nn[jetI] == jetB) {
                        setNNNoCross(jetI, head, tail);
                        diJ(jetI);
                        diJH[jetI] = rH;
                        diJL[jetI] = rL;
                    }
                    distance(jetI, jetB);
                    if (distLessThanNN(jetI)) {
                        if (jetI != jetB) {
                            setNNFromDist(jetI, jetB);
                            diJ(jetI);
                            diJH[jetI] = rH;
                            diJL[jetI] = rL;
                        }
                    }
                    if (distLessThanNN(jetB)) {
                        if (jetI != jetB) {
                            setNNFromDist(jetB, jetI);
                        }
                    }
                    if (nn[jetI] == tail) {
                        nn[jetI] = jetA;
                    }
                }
                diJ(jetB);
                diJH[jetB] = rH;
                diJL[jetB] = rL;
            } else {
                for (int jetI = head; jetI != tail; jetI++) {
                    if (nn[jetI] == jetA) {
                        setNNNoCross(jetI, head, tail);
                        diJ(jetI);
                        diJH[jetI] = rH;
                        diJL[jetI] = rL;
                    }
                    if (nn[jetI] == tail) {
                        nn[jetI] = jetA;
                    }
                }
            }
        }
    }
}
