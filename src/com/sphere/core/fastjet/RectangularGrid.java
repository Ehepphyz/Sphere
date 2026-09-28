package com.sphere.core.fastjet;

/**
 * A grid of equal cells in rapidity and azimuth, fastjet::RectangularGrid,
 * optionally keeping only the cells whose centre a selector accepts.
 */
public class RectangularGrid {

    private double ymax, ymin, requestedDrap, requestedDphi;
    private double dy, dphi, cellArea, inverseDy, inverseDphi;
    private int ny, nphi, ntotal = -1, ngood = -1;
    private Selector tileSelector = new Selector();
    private boolean[] isGood;

    public RectangularGrid(double rapmax, double cellSize) {
        this.ymax = rapmax;
        this.ymin = -rapmax;
        this.requestedDrap = cellSize;
        this.requestedDphi = cellSize;
        setupGrid();
    }

    public RectangularGrid(double rapmin, double rapmax, double drap, double dphi, Selector tileSelector) {
        this.ymax = rapmax;
        this.ymin = rapmin;
        this.requestedDrap = drap;
        this.requestedDphi = dphi;
        this.tileSelector = tileSelector == null ? new Selector() : tileSelector;
        setupGrid();
    }

    public RectangularGrid(RectangularGrid o) {
        ymax = o.ymax;
        ymin = o.ymin;
        requestedDrap = o.requestedDrap;
        requestedDphi = o.requestedDphi;
        dy = o.dy;
        dphi = o.dphi;
        cellArea = o.cellArea;
        inverseDy = o.inverseDy;
        inverseDphi = o.inverseDphi;
        ny = o.ny;
        nphi = o.nphi;
        ntotal = o.ntotal;
        ngood = o.ngood;
        tileSelector = o.tileSelector;
        isGood = o.isGood;
    }

    private void setupGrid() {
        if (!(ymax > ymin) || !(requestedDrap > 0) || !(requestedDphi > 0)) {
            throw new FastJetException("RectangularGrid: invalid rapidity range or cell size");
        }
        final double nyDouble = (ymax - ymin) / requestedDrap;
        ny = Math.max((int) (nyDouble + 0.5), 1);
        dy = (ymax - ymin) / ny;
        inverseDy = ny / (ymax - ymin);
        nphi = (int) (PseudoJet.TWOPI / requestedDphi + 0.5);
        dphi = PseudoJet.TWOPI / nphi;
        inverseDphi = nphi / PseudoJet.TWOPI;
        ntotal = nphi * ny;
        cellArea = dy * dphi;
        if (tileSelector.worker() != null) {
            isGood = new boolean[ntotal];
            ngood = 0;
            for (int i = 0; i < ntotal; i++) {
                final int iphi = i % nphi;
                final int irap = i / nphi;
                final double phi = (iphi + 0.5) * dphi;
                final double rap = (irap + 0.5) * dy + ymin;
                isGood[i] = tileSelector.pass(PseudoJet.ptYPhiM(1.0, rap, phi, 0.0));
                if (isGood[i]) ngood++;
            }
        } else {
            ngood = ntotal;
        }
    }

    /** The cell of a particle, or -1 outside the grid. */
    public int tileIndex(PseudoJet p) {
        final double yMinusYmin = p.rap() - ymin;
        if (yMinusYmin < 0) return -1;
        final int iy = (int) (yMinusYmin * inverseDy);
        if (iy >= ny) return -1;
        int iphi = (int) (p.phi() * inverseDphi);
        if (iphi == nphi) iphi = 0;
        return iy * nphi + iphi;
    }

    public int nTiles() { return ntotal; }
    public int nGoodTiles() { return ngood; }
    public boolean tileIsGood(int itile) { return tileSelector.worker() == null || isGood[itile]; }
    public boolean allTilesGood() { return nGoodTiles() == nTiles(); }
    public boolean allTilesEqualArea() { return true; }
    public double tileArea(int itile) { return meanTileArea(); }
    public double meanTileArea() { return dphi * dy; }
    public double rapmin() { return ymin; }
    public double rapmax() { return ymax; }
    public double drap() { return dy; }
    public double dphi() { return dphi; }
    public boolean isInitialised() { return ntotal > 0; }

    public String description() {
        if (!isInitialised()) {
            return "Uninitialised rectangular grid";
        }
        final StringBuilder o = new StringBuilder();
        o.append("rectangular grid with rapidity extent ").append(Fmt.g(ymin)).append(" < rap < ").append(Fmt.g(ymax))
         .append(", tile size drap x dphi = ").append(Fmt.g(dy)).append(" x ").append(Fmt.g(dphi));
        if (tileSelector.worker() != null) {
            o.append(", good tiles are those that pass selector ").append(tileSelector.description());
        }
        return o.toString();
    }
}
