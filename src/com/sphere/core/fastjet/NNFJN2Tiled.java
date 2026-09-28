package com.sphere.core.fastjet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * fastjet::NNFJN2Tiled: {@link NNFJN2Plain} with the jets kept in tiles of
 * the (rapidity, phi) plane at least as large as the reach of the
 * geometrical distance, so that a jet's nearest neighbour is looked for in
 * the 3x3 tiles around it only, as in the N2Tiled strategy.
 *
 * @param <B> the brief jet
 */
public final class NNFJN2Tiled<B extends NNFJTiledBriefJet<B>> {

    private static final int N_TILE_NEIGHBOURS = 9;

    private final Function<? super PseudoJet, ? extends B> factory;
    private final boolean dd;
    private final double requestedTileSize;

    // the brief jets, by slot
    private Object[] bj;
    private double[] nnH;
    private double[] nnL;
    private int[] nn;
    private int[] previous;
    private int[] next;
    private int[] tileIndex;
    private int[] diJPosn;
    private int[] index;
    private int[] whereIs;
    private int n;

    // the compact table of d_iJ
    private double[] diJH;
    private double[] diJL;
    private int[] diJJet;

    // the tiles: each lists its neighbourhood, itself first, then the tiles
    // on its left, then from rhStart those on its right
    private int[][] neighbours;
    private int[] rhStart;
    private int[] tileHead;
    private boolean[] tagged;
    private int[] tileUnion;
    private double tilesRapMin;
    private double tilesRapMax;
    private double tileSizeRap;
    private double tileSizePhi;
    private int nTilesPhi;
    private int tilesIrapMin;
    private int tilesIrapMax;

    private double lastLow;
    private double prodL;

    public NNFJN2Tiled(List<PseudoJet> jets, double requestedTileSize,
                       Function<? super PseudoJet, ? extends B> factory) {
        this(jets, requestedTileSize, factory, Precision.DOUBLE);
    }

    public NNFJN2Tiled(List<PseudoJet> jets, double requestedTileSize,
                       Function<? super PseudoJet, ? extends B> factory, Precision precision) {
        this.factory = factory;
        this.requestedTileSize = requestedTileSize;
        this.dd = precision == Precision.DD;
        start(jets);
    }

    public void start(List<PseudoJet> jets) {
        initialiseTiles(jets);
        n = jets.size();
        bj = new Object[n];
        nnH = new double[n];
        nnL = new double[n];
        nn = new int[n];
        previous = new int[n];
        next = new int[n];
        tileIndex = new int[n];
        diJPosn = new int[n];
        index = new int[n];
        whereIs = new int[Math.max(2 * n, 1)];
        tileUnion = new int[3 * N_TILE_NEIGHBOURS];
        for (int i = 0; i < n; i++) {
            setJetInfo(i, jets.get(i), i);
            whereIs[i] = i;
        }
        for (int t = 0; t < tileHead.length; t++) {
            for (int a = tileHead[t]; a >= 0; a = next[a]) {
                for (int b = tileHead[t]; b != a; b = next[b]) {
                    pair(a, b);
                }
            }
            final int[] near = neighbours[t];
            for (int r = rhStart[t]; r < near.length; r++) {
                for (int a = tileHead[t]; a >= 0; a = next[a]) {
                    for (int b = tileHead[near[r]]; b >= 0; b = next[b]) {
                        pair(a, b);
                    }
                }
            }
        }
        diJH = new double[n];
        diJL = new double[n];
        diJJet = new int[n];
        for (int i = 0; i < n; i++) {
            computeDiJ(i, i);
            diJJet[i] = i;
            diJPosn[i] = i;
        }
    }

    private void pair(int a, int b) {
        final double dH = jet(a).geometricalDistance(jet(b));
        final double dL = jet(a).lowWord();
        if (NNH.lt(dH, dL, nnH[a], nnL[a])) {
            nnH[a] = dH;
            nnL[a] = dL;
            nn[a] = b;
        }
        if (NNH.lt(dH, dL, nnH[b], nnL[b])) {
            nnH[b] = dH;
            nnL[b] = dL;
            nn[b] = a;
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
        final int a = diJJet[best];
        ab[0] = index[a];
        ab[1] = nn[a] >= 0 ? index[nn[a]] : -1;
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
        removeFromTiles(a);
        int nNear = addUntaggedNeighbours(tileIndex[a], 0);
        n--;
        removeFromDiJ(a);
        for (int it = 0; it < nNear; it++) {
            final int t = tileUnion[it];
            tagged[t] = false;
            for (int i = tileHead[t]; i >= 0; i = next[i]) {
                if (nn[i] == a) {
                    searchNN(i, t);
                    computeDiJ(i, diJPosn[i]);
                }
            }
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
        removeFromTiles(a);
        final int oldBTile = tileIndex[b];
        removeFromTiles(b);
        setJetInfo(b, jet, jetIndex);
        if (jetIndex >= whereIs.length) whereIs = Arrays.copyOf(whereIs, Math.max(2 * jetIndex, jetIndex + 1));
        whereIs[jetIndex] = b;
        int nNear = addUntaggedNeighbours(tileIndex[a], 0);
        if (tileIndex[b] != tileIndex[a]) {
            nNear = addUntaggedNeighbours(tileIndex[b], nNear);
        }
        if (oldBTile != tileIndex[a] && oldBTile != tileIndex[b]) {
            nNear = addUntaggedNeighbours(oldBTile, nNear);
        }
        n--;
        removeFromDiJ(a);
        final B jetB = jet(b);
        for (int it = 0; it < nNear; it++) {
            final int t = tileUnion[it];
            tagged[t] = false;
            for (int i = tileHead[t]; i >= 0; i = next[i]) {
                if (nn[i] == a || nn[i] == b) {
                    searchNN(i, t);
                    computeDiJ(i, diJPosn[i]);
                }
                final double dH = jet(i).geometricalDistance(jetB);
                final double dL = jet(i).lowWord();
                if (NNH.lt(dH, dL, nnH[i], nnL[i]) && i != b) {
                    nnH[i] = dH;
                    nnL[i] = dL;
                    nn[i] = b;
                    computeDiJ(i, diJPosn[i]);
                }
                if (NNH.lt(dH, dL, nnH[b], nnL[b]) && i != b) {
                    nnH[b] = dH;
                    nnL[b] = dL;
                    nn[b] = i;
                }
            }
        }
        computeDiJ(b, diJPosn[b]);
    }

    /** Moves the last d_iJ entry into the place of a's. */
    private void removeFromDiJ(int a) {
        final int last = diJJet[n];
        final int posn = diJPosn[a];
        diJPosn[last] = posn;
        diJH[posn] = diJH[n];
        diJL[posn] = diJL[n];
        diJJet[posn] = last;
    }

    /** The nearest neighbour of jet i among the tiles around tile t. */
    private void searchNN(int i, int t) {
        final B ji = jet(i);
        nnH[i] = ji.geometricalBeamDistance();
        nnL[i] = ji.lowWord();
        nn[i] = -1;
        for (int near : neighbours[t]) {
            for (int j = tileHead[near]; j >= 0; j = next[j]) {
                final double dH = ji.geometricalDistance(jet(j));
                final double dL = ji.lowWord();
                if (NNH.lt(dH, dL, nnH[i], nnL[i]) && j != i) {
                    nnH[i] = dH;
                    nnL[i] = dL;
                    nn[i] = j;
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private B jet(int slot) {
        return (B) bj[slot];
    }

    private void computeDiJ(int s, int posn) {
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
        diJH[posn] = mul(nnH[s], nnL[s], mH, mL);
        diJL[posn] = prodL;
    }

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

    /* ------------------------------------------------------------------ */
    /* Tiles                                                               */
    /* ------------------------------------------------------------------ */

    private void initialiseTiles(List<PseudoJet> particles) {
        final double defaultSize = requestedTileSize > 0.1 ? requestedTileSize : 0.1;
        tileSizeRap = defaultSize;
        nTilesPhi = (int) Math.floor(PseudoJet.TWOPI / defaultSize);
        if (nTilesPhi < 3) nTilesPhi = 3;
        tileSizePhi = PseudoJet.TWOPI / nTilesPhi;
        final double[] extent = BriefJetEngine.tilingExtent(new ArrayList<>(particles));
        tilesRapMin = extent[0];
        tilesRapMax = extent[1];
        tilesIrapMin = (int) Math.floor(tilesRapMin / tileSizeRap);
        tilesIrapMax = (int) Math.floor(tilesRapMax / tileSizeRap);
        tilesRapMin = tilesIrapMin * tileSizeRap;
        tilesRapMax = tilesIrapMax * tileSizeRap;
        final int nTiles = (tilesIrapMax - tilesIrapMin + 1) * nTilesPhi;
        neighbours = new int[nTiles][];
        rhStart = new int[nTiles];
        tileHead = new int[nTiles];
        tagged = new boolean[nTiles];
        final int[] buf = new int[N_TILE_NEIGHBOURS];
        for (int irap = tilesIrapMin; irap <= tilesIrapMax; irap++) {
            for (int iphi = 0; iphi < nTilesPhi; iphi++) {
                final int t = tileIndex(irap, iphi);
                tileHead[t] = -1;
                int k = 0;
                buf[k++] = t;
                if (irap > tilesIrapMin) {
                    for (int idphi = -1; idphi <= 1; idphi++) buf[k++] = tileIndex(irap - 1, iphi + idphi);
                }
                buf[k++] = tileIndex(irap, iphi - 1);
                rhStart[t] = k;
                buf[k++] = tileIndex(irap, iphi + 1);
                if (irap < tilesIrapMax) {
                    for (int idphi = -1; idphi <= 1; idphi++) buf[k++] = tileIndex(irap + 1, iphi + idphi);
                }
                neighbours[t] = Arrays.copyOf(buf, k);
            }
        }
    }

    private int tileIndex(int irap, int iphi) {
        return (irap - tilesIrapMin) * nTilesPhi + (iphi + nTilesPhi) % nTilesPhi;
    }

    private int tileIndex(double rap, double phi) {
        int irap;
        if (rap <= tilesRapMin) {
            irap = 0;
        } else if (rap >= tilesRapMax) {
            irap = tilesIrapMax - tilesIrapMin;
        } else {
            irap = (int) ((rap - tilesRapMin) / tileSizeRap);
            if (irap > tilesIrapMax - tilesIrapMin) irap = tilesIrapMax - tilesIrapMin;
        }
        final int iphi = (int) ((phi + PseudoJet.TWOPI) / tileSizePhi) % nTilesPhi;
        return iphi + irap * nTilesPhi;
    }

    private void setJetInfo(int s, PseudoJet jet, int jetIndex) {
        final B b = factory.apply(jet);
        bj[s] = b;
        index[s] = jetIndex;
        nnH[s] = b.geometricalBeamDistance();
        nnL[s] = b.lowWord();
        nn[s] = -1;
        final int t = tileIndex(b.rap(), b.phi());
        tileIndex[s] = t;
        previous[s] = -1;
        next[s] = tileHead[t];
        if (next[s] >= 0) previous[next[s]] = s;
        tileHead[t] = s;
    }

    private void removeFromTiles(int s) {
        final int t = tileIndex[s];
        if (previous[s] < 0) {
            tileHead[t] = next[s];
        } else {
            next[previous[s]] = next[s];
        }
        if (next[s] >= 0) previous[next[s]] = previous[s];
    }

    private int addUntaggedNeighbours(int t, int nNear) {
        for (int near : neighbours[t]) {
            if (!tagged[near]) {
                tagged[near] = true;
                tileUnion[nNear++] = near;
            }
        }
        return nNear;
    }
}
