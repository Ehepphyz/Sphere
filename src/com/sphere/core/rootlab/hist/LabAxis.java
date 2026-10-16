package com.sphere.core.rootlab.hist;

import com.sphere.core.rootio.RObject;

/**
 * A TAxis as the ROOT Lab's histograms use it: fixed or variable bins, and
 * FindFixBin and GetBinCenter written as hist/hist/src/TAxis.cxx writes them,
 * so that a value lands in the bin ROOT puts it in, edges included.
 */
public final class LabAxis {

    public final int nbins;
    public final double xmin;
    public final double xmax;
    /** Bin edges when the binning is variable; empty for fixed bins. */
    public final double[] xbins;

    public LabAxis(int nbins, double xmin, double xmax) {
        this(nbins, xmin, xmax, new double[0]);
    }

    public LabAxis(int nbins, double xmin, double xmax, double[] xbins) {
        this.nbins = nbins;
        this.xmin = xmin;
        this.xmax = xmax;
        this.xbins = xbins == null ? new double[0] : xbins;
    }

    /** The axis of a TAxis read from a file. */
    public static LabAxis of(RObject axis) {
        if (axis == null) {
            return new LabAxis(1, 0, 1);
        }
        final Object edges = axis.get("fXbins");
        double[] xbins = new double[0];
        if (edges instanceof double[] d) {
            xbins = d;
        } else if (edges instanceof float[] f) {
            xbins = new double[f.length];
            for (int i = 0; i < f.length; i++) {
                xbins[i] = f[i];
            }
        }
        return new LabAxis(axis.integer("fNbins"), axis.real("fXmin"), axis.real("fXmax"), xbins);
    }

    /** TAxis::FindFixBin, including the correction ROOT applies near bin edges. */
    public int findBin(double x) {
        if (x < xmin) {
            return 0;
        }
        if (!(x < xmax)) {                       // overflow, NaN included
            return nbins + 1;
        }
        if (xbins.length == 0) {
            final double width = (xmax - xmin) / nbins;
            final int approxBin = (int) ((x - xmin) / width);
            return 1 + approxBin - (x < xmin + width * approxBin ? 1 : 0)
                     + (xmin + width * (approxBin + 1) <= x ? 1 : 0);
        }
        return 1 + binarySearch(xbins, x);
    }

    /** TMath::BinarySearch: the index of the last element not greater than x, -1 when there is none. */
    static int binarySearch(double[] array, double value) {
        int nabove = array.length + 1;
        int nbelow = 0;
        while (nabove - nbelow > 1) {
            final int middle = (nabove + nbelow) / 2;
            if (value == array[middle - 1]) {
                return middle - 1;
            }
            if (value < array[middle - 1]) {
                nabove = middle;
            } else {
                nbelow = middle;
            }
        }
        return nbelow - 1;
    }

    public double binCenter(int bin) {
        if (xbins.length == 0 || bin < 1 || bin > nbins) {
            final double binwidth = (xmax - xmin) / (double) nbins;
            return xmin + (bin - 0.5) * binwidth;
        }
        final double binwidth = xbins[bin] - xbins[bin - 1];
        return xbins[bin - 1] + 0.5 * binwidth;
    }

    public double lowEdge(int bin) {
        if (xbins.length != 0 && bin > 0 && bin <= nbins) {
            return xbins[bin - 1];
        }
        final double binwidth = (xmax - xmin) / (double) nbins;
        return xmin + (bin - 1) * binwidth;
    }

    public double upEdge(int bin) {
        return lowEdge(bin + 1);
    }

    @Override
    public String toString() {
        return nbins + " bins [" + xmin + ", " + xmax + "]" + (xbins.length > 0 ? " variable" : "");
    }
}
