package com.sphere.core.fastjet;

import java.util.Arrays;

/**
 * The tiled N^2 strategies of ClusterSequence_TiledN2.cc: N2PoorTiled,
 * N2Tiled and N2MinHeapTiled.
 *
 * The rapidity-azimuth plane is cut into tiles at least R wide, so a jet's
 * neighbour is searched only in the nine tiles around its own. Tiles and jets
 * are indices into arrays rather than pointers; the order in which they are
 * visited is FastJet's, so the same neighbour wins every tie.
 */
final class TiledEngine extends BriefJetEngine {

    static final int N_TILE_NEIGHBOURS = 9;

    // tiles
    private int[] tileHead;
    private boolean[] tileTagged;
    /** For each tile: self, then the left neighbours, then the right ones. */
    private int[][] tileNeighbours;
    /** Where the right-hand neighbours start in tileNeighbours[t]. */
    private int[] tileRhStart;
    private double tilesEtaMin, tilesEtaMax, tileSizeEta, tileSizePhi;
    private int nTilesPhi, tilesIetaMin, tilesIetaMax;

    // jets in tiles
    private int[] tileIndex, previous, next, diJPosn;

    TiledEngine(ClusterSequence cs) {
        super(cs);
    }

    @Override
    void allocate(int n) {
        super.allocate(n);
        tileIndex = new int[n];
        previous = new int[n];
        next = new int[n];
        diJPosn = new int[n];
    }

    @Override
    void copySlot(int from, int to) {
        super.copySlot(from, to);
        tileIndex[to] = tileIndex[from];
        previous[to] = previous[from];
        next[to] = next[from];
        diJPosn[to] = diJPosn[from];
    }

    private int tileIndexOf(int ieta, int iphi) {
        return (ieta - tilesIetaMin) * nTilesPhi + (iphi + nTilesPhi) % nTilesPhi;
    }

    private void initialiseTiles() {
        double defaultSize = Math.max(0.1, rParamForTiles());
        if (dd) {
            // A hair wider, so that a pair closer than R in 106 bits is never
            // two tiles apart because of the rounding of the doubles used here.
            defaultSize *= 1.0 + 1e-12;
        }
        tileSizeEta = defaultSize;
        nTilesPhi = Math.max(3, (int) Math.floor(TWOPI / defaultSize));
        tileSizePhi = TWOPI / nTilesPhi;

        final double[] extent = tilingExtent(jets);
        tilesEtaMin = extent[0];
        tilesEtaMax = extent[1];
        tilesIetaMin = (int) Math.floor(tilesEtaMin / tileSizeEta);
        tilesIetaMax = (int) Math.floor(tilesEtaMax / tileSizeEta);
        tilesEtaMin = tilesIetaMin * tileSizeEta;
        tilesEtaMax = tilesIetaMax * tileSizeEta;

        final int nTiles = (tilesIetaMax - tilesIetaMin + 1) * nTilesPhi;
        tileHead = new int[nTiles];
        tileTagged = new boolean[nTiles];
        tileNeighbours = new int[nTiles][];
        tileRhStart = new int[nTiles];
        final int[] buffer = new int[N_TILE_NEIGHBOURS];
        for (int ieta = tilesIetaMin; ieta <= tilesIetaMax; ieta++) {
            for (int iphi = 0; iphi < nTilesPhi; iphi++) {
                final int t = tileIndexOf(ieta, iphi);
                tileHead[t] = -1;
                int k = 0;
                buffer[k++] = t;
                if (ieta > tilesIetaMin) {
                    for (int idphi = -1; idphi <= +1; idphi++) {
                        buffer[k++] = tileIndexOf(ieta - 1, iphi + idphi);
                    }
                }
                buffer[k++] = tileIndexOf(ieta, iphi - 1);
                tileRhStart[t] = k;
                buffer[k++] = tileIndexOf(ieta, iphi + 1);
                if (ieta < tilesIetaMax) {
                    for (int idphi = -1; idphi <= +1; idphi++) {
                        buffer[k++] = tileIndexOf(ieta + 1, iphi + idphi);
                    }
                }
                tileNeighbours[t] = Arrays.copyOf(buffer, k);
                tileTagged[t] = false;
            }
        }
    }

    private double rParamForTiles() {
        return cs.rParam;
    }

    private int tileIndexOfPoint(double eta, double phi) {
        int ieta;
        if (eta <= tilesEtaMin) {
            ieta = 0;
        } else if (eta >= tilesEtaMax) {
            ieta = tilesIetaMax - tilesIetaMin;
        } else {
            ieta = (int) ((eta - tilesEtaMin) / tileSizeEta);
            if (ieta > tilesIetaMax - tilesIetaMin) {
                ieta = tilesIetaMax - tilesIetaMin;
            }
        }
        final int iphi = ((int) ((phi + TWOPI) / tileSizePhi)) % nTilesPhi;
        return iphi + ieta * nTilesPhi;
    }

    /** _tj_set_jetinfo: the brief jet, and its place at the head of its tile's list. */
    private void tjSetJetInfo(int s, int jetIndex) {
        setJetInfo(s, jetIndex);
        tileIndex[s] = tileIndexOfPoint(etaH[s], phiH[s]);
        final int t = tileIndex[s];
        previous[s] = -1;
        next[s] = tileHead[t];
        if (next[s] >= 0) {
            previous[next[s]] = s;
        }
        tileHead[t] = s;
    }

    private void removeFromTiles(int s) {
        final int t = tileIndex[s];
        if (previous[s] < 0) {
            tileHead[t] = next[s];
        } else {
            next[previous[s]] = next[s];
        }
        if (next[s] >= 0) {
            previous[next[s]] = previous[s];
        }
    }

    private int addNeighboursToTileUnion(int tile, int[] union, int nNear) {
        for (int nt : tileNeighbours[tile]) {
            union[nNear++] = nt;
        }
        return nNear;
    }

    private int addUntaggedNeighboursToTileUnion(int tile, int[] union, int nNear) {
        for (int nt : tileNeighbours[tile]) {
            if (!tileTagged[nt]) {
                tileTagged[nt] = true;
                union[nNear++] = nt;
            }
        }
        return nNear;
    }

    /** The initial neighbours: within each tile, then with the right-hand tiles. */
    private void initialNeighbours() {
        for (int t = 0; t < tileHead.length; t++) {
            for (int a = tileHead[t]; a >= 0; a = next[a]) {
                for (int b = tileHead[t]; b != a; b = next[b]) {
                    dist(a, b);
                    if (distLessThanNN(a)) setNNFromDist(a, b);
                    if (distLessThanNN(b)) setNNFromDist(b, a);
                }
            }
            final int[] nb = tileNeighbours[t];
            for (int k = tileRhStart[t]; k < nb.length; k++) {
                for (int a = tileHead[t]; a >= 0; a = next[a]) {
                    for (int b = tileHead[nb[k]]; b >= 0; b = next[b]) {
                        dist(a, b);
                        if (distLessThanNN(a)) setNNFromDist(a, b);
                        if (distLessThanNN(b)) setNNFromDist(b, a);
                    }
                }
            }
        }
    }

    /** Recomputes jetI's neighbour over the tiles around the tile it sits in. */
    private void resetNNInNeighbourhood(int jetI, int tile) {
        nnH[jetI] = r2H;
        nnL[jetI] = r2L;
        nn[jetI] = -1;
        for (int nt : tileNeighbours[tile]) {
            for (int jetJ = tileHead[nt]; jetJ >= 0; jetJ = next[jetJ]) {
                dist(jetI, jetJ);
                if (distLessThanNN(jetI) && jetJ != jetI) {
                    setNNFromDist(jetI, jetJ);
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* N2PoorTiled                                                         */
    /* ------------------------------------------------------------------ */

    void poorTiled() {
        initialiseTiles();
        int n = jets.size();
        allocate(n);
        int oldBTile = 0;
        final int[] tileUnion = new int[3 * N_TILE_NEIGHBOURS];
        for (int i = 0; i < n; i++) {
            tjSetJetInfo(i, i);
        }
        int tail = n;
        final int head = 0;
        initialNeighbours();
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
            if (jetB >= 0) {
                if (jetA < jetB) {
                    final int t = jetA;
                    jetA = jetB;
                    jetB = t;
                }
                final int nnew = cs.doIJRecombinationStep(jetsIndex[jetA], jetsIndex[jetB], rH, rL);
                removeFromTiles(jetA);
                oldBTile = tileIndex[jetB];
                removeFromTiles(jetB);
                tjSetJetInfo(jetB, nnew);
            } else {
                cs.doIBRecombinationStep(jetsIndex[jetA], rH, rL);
                removeFromTiles(jetA);
            }

            int nNear = addNeighboursToTileUnion(tileIndex[jetA], tileUnion, 0);
            if (jetB >= 0) {
                boolean sortIt = false;
                if (tileIndex[jetB] != tileIndex[jetA]) {
                    sortIt = true;
                    nNear = addNeighboursToTileUnion(tileIndex[jetB], tileUnion, nNear);
                }
                if (oldBTile != tileIndex[jetA] && oldBTile != tileIndex[jetB]) {
                    sortIt = true;
                    nNear = addNeighboursToTileUnion(oldBTile, tileUnion, nNear);
                }
                if (sortIt) {
                    Arrays.sort(tileUnion, 0, nNear);
                    int nnn = 1;
                    for (int i = 1; i < nNear; i++) {
                        if (tileUnion[i] != tileUnion[nnn - 1]) {
                            tileUnion[nnn] = tileUnion[i];
                            nnn++;
                        }
                    }
                    nNear = nnn;
                }
            }

            tail--;
            n--;
            if (jetA != tail) {
                copySlot(tail, jetA);
                diJH[jetA] = diJH[tail];
                diJL[jetA] = diJL[tail];
                if (previous[jetA] < 0) {
                    tileHead[tileIndex[jetA]] = jetA;
                } else {
                    next[previous[jetA]] = jetA;
                }
                if (next[jetA] >= 0) {
                    previous[next[jetA]] = jetA;
                }
            }

            for (int itile = 0; itile < nNear; itile++) {
                final int tile = tileUnion[itile];
                for (int jetI = tileHead[tile]; jetI >= 0; jetI = next[jetI]) {
                    if (nn[jetI] == jetA || (nn[jetI] == jetB && jetB >= 0)) {
                        resetNNInNeighbourhood(jetI, tile);
                        diJ(jetI);
                        diJH[jetI] = rH;
                        diJL[jetI] = rL;
                    }
                    if (jetB >= 0) {
                        dist(jetI, jetB);
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
                    }
                }
            }
            if (jetB >= 0) {
                diJ(jetB);
                diJH[jetB] = rH;
                diJL[jetB] = rL;
            }
            // pointers to the old tail now point to where it was moved
            for (int nt : tileNeighbours[tileIndex[tail]]) {
                for (int jetJ = tileHead[nt]; jetJ >= 0; jetJ = next[jetJ]) {
                    if (nn[jetJ] == tail) {
                        nn[jetJ] = jetA;
                    }
                }
            }
            if (jetB >= 0) {
                diJ(jetB);
                diJH[jetB] = rH;
                diJL[jetB] = rL;
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* N2Tiled                                                             */
    /* ------------------------------------------------------------------ */

    void fasterTiled() {
        initialiseTiles();
        int n = jets.size();
        allocate(n);
        int oldBTile = 0;
        final int[] tileUnion = new int[3 * N_TILE_NEIGHBOURS];
        for (int i = 0; i < n; i++) {
            tjSetJetInfo(i, i);
        }
        initialNeighbours();

        // The compact diJ table and its link to the jets.
        final double[] diJH = new double[n];
        final double[] diJL = new double[n];
        final int[] diJJet = new int[n];
        for (int i = 0; i < n; i++) {
            diJ(i);
            diJH[i] = rH;
            diJL[i] = rL;
            diJJet[i] = i;
            diJPosn[i] = i;
        }

        while (n > 0) {
            int best = 0;
            double minH = diJH[0];
            double minL = diJL[0];
            for (int here = 1; here < n; here++) {
                if (lt(diJH[here], diJL[here], minH, minL)) {
                    best = here;
                    minH = diJH[here];
                    minL = diJL[here];
                }
            }
            int jetA = diJJet[best];
            int jetB = nn[jetA];
            normalise(minH, minL);
            if (jetB >= 0) {
                if (jetA < jetB) {
                    final int t = jetA;
                    jetA = jetB;
                    jetB = t;
                }
                final int nnew = cs.doIJRecombinationStep(jetsIndex[jetA], jetsIndex[jetB], rH, rL);
                removeFromTiles(jetA);
                oldBTile = tileIndex[jetB];
                removeFromTiles(jetB);
                tjSetJetInfo(jetB, nnew);
            } else {
                cs.doIBRecombinationStep(jetsIndex[jetA], rH, rL);
                removeFromTiles(jetA);
            }

            int nNear = addUntaggedNeighboursToTileUnion(tileIndex[jetA], tileUnion, 0);
            if (jetB >= 0) {
                if (tileIndex[jetB] != tileIndex[jetA]) {
                    nNear = addUntaggedNeighboursToTileUnion(tileIndex[jetB], tileUnion, nNear);
                }
                if (oldBTile != tileIndex[jetA] && oldBTile != tileIndex[jetB]) {
                    nNear = addUntaggedNeighboursToTileUnion(oldBTile, tileUnion, nNear);
                }
            }

            n--;
            // compact the table: the last entry takes jetA's place
            final int lastJet = diJJet[n];
            diJPosn[lastJet] = diJPosn[jetA];
            final int pos = diJPosn[jetA];
            diJH[pos] = diJH[n];
            diJL[pos] = diJL[n];
            diJJet[pos] = diJJet[n];

            for (int itile = 0; itile < nNear; itile++) {
                final int tile = tileUnion[itile];
                tileTagged[tile] = false;
                for (int jetI = tileHead[tile]; jetI >= 0; jetI = next[jetI]) {
                    if (nn[jetI] == jetA || (nn[jetI] == jetB && jetB >= 0)) {
                        resetNNInNeighbourhood(jetI, tile);
                        diJ(jetI);
                        diJH[diJPosn[jetI]] = rH;
                        diJL[diJPosn[jetI]] = rL;
                    }
                    if (jetB >= 0) {
                        dist(jetI, jetB);
                        if (distLessThanNN(jetI)) {
                            if (jetI != jetB) {
                                setNNFromDist(jetI, jetB);
                                diJ(jetI);
                                diJH[diJPosn[jetI]] = rH;
                                diJL[diJPosn[jetI]] = rL;
                            }
                        }
                        if (distLessThanNN(jetB)) {
                            if (jetI != jetB) {
                                setNNFromDist(jetB, jetI);
                            }
                        }
                    }
                }
            }
            if (jetB >= 0) {
                diJ(jetB);
                diJH[diJPosn[jetB]] = rH;
                diJL[diJPosn[jetB]] = rL;
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* N2MinHeapTiled                                                      */
    /* ------------------------------------------------------------------ */

    void minheapFasterTiled() {
        initialiseTiles();
        int n = jets.size();
        allocate(n);
        int oldBTile = 0;
        final int[] tileUnion = new int[3 * N_TILE_NEIGHBOURS];
        for (int i = 0; i < n; i++) {
            tjSetJetInfo(i, i);
        }
        initialNeighbours();

        final double[] vH = new double[n];
        final double[] vL = new double[n];
        for (int i = 0; i < n; i++) {
            diJ(i);
            vH[i] = rH;
            vL[i] = rL;
            diJPosn[i] = 0; // "update done"
        }
        final MinHeap minheap = new MinHeap(vH, vL, n);
        int[] forMinheap = new int[Math.max(16, n)];
        int nForMinheap = 0;

        while (n > 0) {
            normalise(minheap.minvalH(), minheap.minvalL());
            final double dijH = rH;
            final double dijL = rL;
            int jetA = minheap.minloc();
            int jetB = nn[jetA];
            if (jetB >= 0) {
                if (jetA < jetB) {
                    final int t = jetA;
                    jetA = jetB;
                    jetB = t;
                }
                final int nnew = cs.doIJRecombinationStep(jetsIndex[jetA], jetsIndex[jetB], dijH, dijL);
                removeFromTiles(jetA);
                oldBTile = tileIndex[jetB];
                removeFromTiles(jetB);
                tjSetJetInfo(jetB, nnew);
            } else {
                cs.doIBRecombinationStep(jetsIndex[jetA], dijH, dijL);
                removeFromTiles(jetA);
            }
            minheap.remove(jetA);

            int nNear = addUntaggedNeighboursToTileUnion(tileIndex[jetA], tileUnion, 0);
            if (jetB >= 0) {
                if (tileIndex[jetB] != tileIndex[jetA]) {
                    nNear = addUntaggedNeighboursToTileUnion(tileIndex[jetB], tileUnion, nNear);
                }
                if (oldBTile != tileIndex[jetA] && oldBTile != tileIndex[jetB]) {
                    nNear = addUntaggedNeighboursToTileUnion(oldBTile, tileUnion, nNear);
                }
                diJPosn[jetB] = 1;
                if (nForMinheap == forMinheap.length) forMinheap = Arrays.copyOf(forMinheap, nForMinheap * 2);
                forMinheap[nForMinheap++] = jetB;
            }

            for (int itile = 0; itile < nNear; itile++) {
                final int tile = tileUnion[itile];
                tileTagged[tile] = false;
                for (int jetI = tileHead[tile]; jetI >= 0; jetI = next[jetI]) {
                    if (nn[jetI] == jetA || (nn[jetI] == jetB && jetB >= 0)) {
                        nnH[jetI] = r2H;
                        nnL[jetI] = r2L;
                        nn[jetI] = -1;
                        if (diJPosn[jetI] != 1) {
                            diJPosn[jetI] = 1;
                            if (nForMinheap == forMinheap.length) forMinheap = Arrays.copyOf(forMinheap, nForMinheap * 2);
                            forMinheap[nForMinheap++] = jetI;
                        }
                        for (int nt : tileNeighbours[tile]) {
                            for (int jetJ = tileHead[nt]; jetJ >= 0; jetJ = next[jetJ]) {
                                dist(jetI, jetJ);
                                if (distLessThanNN(jetI) && jetJ != jetI) {
                                    setNNFromDist(jetI, jetJ);
                                }
                            }
                        }
                    }
                    if (jetB >= 0) {
                        dist(jetI, jetB);
                        if (distLessThanNN(jetI)) {
                            if (jetI != jetB) {
                                setNNFromDist(jetI, jetB);
                                if (diJPosn[jetI] != 1) {
                                    diJPosn[jetI] = 1;
                                    if (nForMinheap == forMinheap.length) forMinheap = Arrays.copyOf(forMinheap, nForMinheap * 2);
                                    forMinheap[nForMinheap++] = jetI;
                                }
                            }
                        }
                        if (distLessThanNN(jetB)) {
                            if (jetI != jetB) {
                                setNNFromDist(jetB, jetI);
                            }
                        }
                    }
                }
            }

            while (nForMinheap > 0) {
                final int jetI = forMinheap[--nForMinheap];
                diJ(jetI);
                minheap.update(jetI, rH, rL);
                diJPosn[jetI] = 0;
            }
            n--;
        }
    }
}
