package com.sphere.core.minuit;

/**
 * What ROOT's plugin "TMinuitGraph" makes for TMinuit: a TGraph of n points,
 * TGraph(Int_t n, const Double_t *x, const Double_t *y). TMinuit hands it the
 * points of a contour (Contour, MNCONTOUR) or of a scan (SCAN) when graphics
 * mode is on; GetPlot() returns the last one.
 */
public final class TMinuitGraph {

    private final double[] fX;
    private final double[] fY;

    public TMinuitGraph(int n, double[] x, double[] y) {
        final int m = Math.max(n, 0);
        fX = new double[m];
        fY = new double[m];
        System.arraycopy(x, 0, fX, 0, Math.min(m, x.length));
        System.arraycopy(y, 0, fY, 0, Math.min(m, y.length));
    }

    public int GetN() {
        return fX.length;
    }

    /** The x of the points (the graph's own array). */
    public double[] GetX() {
        return fX;
    }

    public double[] GetY() {
        return fY;
    }
}
