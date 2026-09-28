package com.sphere.core.fastjet;

import java.util.ArrayList;

/**
 * The NlnNCam strategies of ClusterSequence_CP2DChan.cc: Cambridge/Aachen is
 * purely geometric, so it is the repeated merging of the closest pair, which
 * Chan's structure finds in log time. The azimuth is made periodic by mirror
 * copies of the points near 0 and 2pi.
 */
final class CP2DChanEngine {

    private final ClusterSequence cs;
    private final ArrayList<PseudoJet> jets;
    private final boolean dd;

    CP2DChanEngine(ClusterSequence cs) {
        this.cs = cs;
        this.jets = cs.internalJets();
        this.dd = cs.dd;
    }

    private void requireCambridge() {
        if (cs.jetAlgorithm != JetAlgorithm.CAMBRIDGE) {
            throw new FastJetException("CP2DChan clustering method called for a jet-finder that is not the cambridge algorithm");
        }
    }

    private boolean onBeam(PseudoJet j) {
        return j.E() == Math.abs(j.pz()) && j.perp2() == 0.0;
    }

    /** Mirrors a point near 0 or 2pi to the other side; {y, yL} or null. */
    private double[] mirror(double y, double yl, double dlim) {
        if (y < dlim) {
            return shiftY(y, yl, true);
        }
        if (ClusterSequence.TWOPI - y < dlim) {
            return shiftY(y, yl, false);
        }
        return null;
    }

    private double[] shiftY(double y, double yl, boolean up) {
        if (!dd) {
            return new double[]{up ? y + ClusterSequence.TWOPI : y - ClusterSequence.TWOPI, 0.0};
        }
        final DD r = up ? new DD(y, yl).add(DD.TWO_PI) : new DD(y, yl).sub(DD.TWO_PI);
        return new double[]{r.hi, r.lo};
    }

    /** Clustering limited to pairs closer than dlim, as _CP2DChan_limited_cluster. */
    void limitedCluster(double dlim) {
        final int n = cs.initialN;
        final int[] orig = new int[2 * n];
        final int[] mir = new int[2 * n];
        final int[] jetIDs = new int[2 * n];
        final double[] cx = new double[2 * n], cxl = new double[2 * n];
        final double[] cy = new double[2 * n], cyl = new double[2 * n];
        final double dlim4mirror = Math.min(dlim, Math.PI);
        double minrap = Double.MAX_VALUE;
        double maxrap = -minrap;
        int coordIndex = -1;
        int nActive = 0;
        for (int jetI = 0; jetI < jets.size(); jetI++) {
            final PseudoJet j = jets.get(jetI);
            if (cs.history.get(j.clusterHistIndex).child != ClusterSequence.INVALID || onBeam(j)) {
                continue;
            }
            nActive++;
            orig[jetI] = ++coordIndex;
            setCoord(cx, cxl, cy, cyl, coordIndex, j);
            jetIDs[coordIndex] = jetI;
            minrap = Math.min(cx[coordIndex], minrap);
            maxrap = Math.max(cx[coordIndex], maxrap);
            final double[] m = mirror(cy[coordIndex], cyl[coordIndex], dlim4mirror);
            if (m != null) {
                mir[jetI] = ++coordIndex;
                cx[coordIndex] = cx[coordIndex - 1];
                cxl[coordIndex] = cxl[coordIndex - 1];
                cy[coordIndex] = m[0];
                cyl[coordIndex] = m[1];
                jetIDs[coordIndex] = jetI;
            } else {
                mir[jetI] = ClusterSequence.INVALID;
            }
        }
        final int nCoords = coordIndex + 1;
        if (nCoords < 2) {
            return;
        }
        final ClosestPair2D cp = new ClosestPair2D(cx, cxl, cy, cyl, nCoords,
            minrap - 1.0, -3.15, maxrap + 1.0, 9.45, dd);
        final int[] toRemove = new int[4];
        final double[][] newPoints = new double[2][];
        final double dlim2 = dlim * dlim;
        while (true) {
            cp.closestPair();
            if (cp.dist2H > dlim2 || (cp.dist2H == dlim2 && cp.dist2L > 0.0)) {
                break;
            }
            final double[] d = normalised(cp.dist2H, cp.dist2L);
            final int jetI = jetIDs[cp.id1];
            final int jetJ = jetIDs[cp.id2];
            final int newjetK = cs.doIJRecombinationStep(jetI, jetJ, d[0], d[1]);
            if (--nActive == 1) {
                break;
            }
            int nr = 0;
            toRemove[nr++] = orig[jetI];
            toRemove[nr++] = orig[jetJ];
            if (mir[jetI] != ClusterSequence.INVALID) toRemove[nr++] = mir[jetI];
            if (mir[jetJ] != ClusterSequence.INVALID) toRemove[nr++] = mir[jetJ];
            final PseudoJet nj = jets.get(newjetK);
            final double[] p = coordOf(nj);
            newPoints[0] = new double[]{p[0], p[1], p[2], p[3]};
            int nNew = 1;
            final double[] m = mirror(p[2], p[3], dlim4mirror);
            if (m != null) {
                newPoints[1] = new double[]{p[0], p[1], m[0], m[1]};
                nNew = 2;
            }
            final int[] newIds = cp.replaceMany(toRemove, nr, newPoints, nNew);
            orig[newjetK] = newIds[0];
            jetIDs[newIds[0]] = newjetK;
            if (newIds.length == 2) {
                mir[newjetK] = newIds[1];
                jetIDs[newIds[1]] = newjetK;
            } else {
                mir[newjetK] = ClusterSequence.INVALID;
            }
        }
    }

    private double[] normalised(double h, double l) {
        if (!dd) {
            return new double[]{h * cs.invR2, 0.0};
        }
        final DD r = new DD(h, l).mul(new DD(cs.invR2, cs.invR2L));
        return new double[]{r.hi, r.lo};
    }

    private void setCoord(double[] cx, double[] cxl, double[] cy, double[] cyl, int k, PseudoJet j) {
        final double[] p = coordOf(j);
        cx[k] = p[0];
        cxl[k] = p[1];
        cy[k] = p[2];
        cyl[k] = p[3];
    }

    private double[] coordOf(PseudoJet j) {
        if (dd) {
            final DD y = j.rapDD();
            final DD f = j.phiDD();
            return new double[]{y.hi, y.lo, f.hi, f.lo};
        }
        return new double[]{j.rap(), 0.0, j.phi(), 0.0};
    }

    void cluster2pi2R() {
        requireCambridge();
        limitedCluster(cs.rParam);
        doCambridgeInclusiveJets();
    }

    void cluster2piMultD() {
        if (cs.rParam >= 0.39) {
            limitedCluster(Math.min(cs.rParam / 2, 0.3));
        }
        cluster2pi2R();
    }

    /** The unlimited variant, _CP2DChan_cluster, with every point mirrored. */
    void cluster() {
        requireCambridge();
        int n = jets.size();
        final int[] orig = new int[2 * n];
        final int[] mir = new int[2 * n];
        final int[] jetIDs = new int[2 * n];
        final double[] cx = new double[2 * n], cxl = new double[2 * n];
        final double[] cy = new double[2 * n], cyl = new double[2 * n];
        double minrap = Double.MAX_VALUE;
        double maxrap = -minrap;
        int coordIndex = 0;
        for (int i = 0; i < n; i++) {
            final PseudoJet j = jets.get(i);
            if (onBeam(j)) {
                orig[i] = ClusterSequence.BEAM_JET;
                mir[i] = ClusterSequence.BEAM_JET;
            } else {
                orig[i] = coordIndex;
                mir[i] = coordIndex + 1;
                final double[] p = coordOf(j);
                cx[coordIndex] = p[0];
                cxl[coordIndex] = p[1];
                cy[coordIndex] = p[2];
                cyl[coordIndex] = p[3];
                final double[] m = shiftY(p[2], p[3], true);
                cx[coordIndex + 1] = p[0];
                cxl[coordIndex + 1] = p[1];
                cy[coordIndex + 1] = m[0];
                cyl[coordIndex + 1] = m[1];
                jetIDs[coordIndex] = i;
                jetIDs[coordIndex + 1] = i;
                minrap = Math.min(cx[coordIndex], minrap);
                maxrap = Math.max(cx[coordIndex], maxrap);
                coordIndex += 2;
            }
        }
        for (int i = n; i < 2 * n; i++) {
            orig[i] = ClusterSequence.INVALID;
        }
        if (coordIndex < 2) {
            doCambridgeInclusiveJets();
            return;
        }
        final ClosestPair2D cp = new ClosestPair2D(cx, cxl, cy, cyl, coordIndex,
            minrap - 1.0, 0.0, maxrap + 1.0, 2 * ClusterSequence.TWOPI, dd);
        while (true) {
            cp.closestPair();
            final double[] d = normalised(cp.dist2H, cp.dist2L);
            if (d[0] > 1.0 || (d[0] == 1.0 && d[1] > 0.0)) {
                break;
            }
            final int jetI = jetIDs[cp.id1];
            final int jetJ = jetIDs[cp.id2];
            final int newjetK = cs.doIJRecombinationStep(jetI, jetJ, d[0], d[1]);
            final PseudoJet nj = jets.get(newjetK);
            final double[] p = coordOf(nj);
            final double[] m = shiftY(p[2], p[3], true);
            final int new0 = cp.replace(orig[jetI], orig[jetJ], p[0], p[1], p[2], p[3]);
            final int new1 = cp.replace(mir[jetI], mir[jetJ], p[0], p[1], m[0], m[1]);
            orig[jetI] = ClusterSequence.INVALID;
            orig[jetJ] = ClusterSequence.INVALID;
            orig[newjetK] = new0;
            mir[newjetK] = new1;
            jetIDs[new0] = newjetK;
            jetIDs[new1] = newjetK;
            n--;
            if (n == 1) {
                break;
            }
        }
        doCambridgeInclusiveJets();
    }

    private void doCambridgeInclusiveJets() {
        final int n = cs.history.size();
        for (int histI = 0; histI < n; histI++) {
            if (cs.history.get(histI).child == ClusterSequence.INVALID) {
                cs.doIBRecombinationStep(cs.history.get(histI).jetpIndex, 1.0, 0.0);
            }
        }
    }
}
