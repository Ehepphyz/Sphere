package com.sphere.core.rootlab.hist;

import com.sphere.core.rootio.RObject;

/**
 * A TH1D, TH2D or TH3D as the ROOT Lab fills and compares it: the cells
 * (under- and overflow included, x fastest), the sums of squared weights, and
 * the running sums ROOT keeps for the statistics. Fill, GetStats, GetMean,
 * GetStdDev, GetSumOfAllWeights and GetEffectiveEntries follow TH1.cxx, TH2.cxx
 * and TH3.cxx statement for statement, so a histogram filled here with the
 * numbers ROOT drew is the histogram ROOT wrote.
 */
public class LabHist {

    public final String className;
    public final String name;
    public final String title;
    public final int dimension;
    public final LabAxis xaxis;
    public final LabAxis yaxis;
    public final LabAxis zaxis;

    /** fArray: the content of every cell. For a profile, the sum of w*y. */
    public final double[] array;
    /** fSumw2: the sum of squared weights per cell; empty until Sumw2. For a profile, the sum of w*y*y. */
    public double[] sumw2 = new double[0];

    public double entries;
    public double tsumw;
    public double tsumw2;
    public double tsumwx;
    public double tsumwx2;
    public double tsumwy;
    public double tsumwy2;
    public double tsumwxy;
    public double tsumwz;
    public double tsumwz2;
    public double tsumwxz;
    public double tsumwyz;

    /** TH1::StatOverflows for this histogram: whether under/overflows enter the statistics. */
    public boolean statOverflows;

    public LabHist(String className, String name, String title, LabAxis x, LabAxis y, LabAxis z) {
        this.className = className;
        this.name = name;
        this.title = title;
        this.xaxis = x;
        this.yaxis = y == null ? new LabAxis(1, 0, 1) : y;
        this.zaxis = z == null ? new LabAxis(1, 0, 1) : z;
        this.dimension = z != null ? 3 : y != null ? 2 : 1;
        this.array = new double[cells()];
    }

    public static LabHist th1d(String name, String title, int nbins, double min, double max) {
        return new LabHist("TH1D", name, title, new LabAxis(nbins, min, max), null, null);
    }

    public static LabHist th2d(String name, String title, int nx, double xmin, double xmax,
                               int ny, double ymin, double ymax) {
        return new LabHist("TH2D", name, title, new LabAxis(nx, xmin, xmax), new LabAxis(ny, ymin, ymax), null);
    }

    public static LabHist th3d(String name, String title, int nx, double xmin, double xmax,
                               int ny, double ymin, double ymax, int nz, double zmin, double zmax) {
        return new LabHist("TH3D", name, title, new LabAxis(nx, xmin, xmax), new LabAxis(ny, ymin, ymax),
                           new LabAxis(nz, zmin, zmax));
    }

    /** fNcells: every cell, the under- and overflow ones of each axis included. */
    public final int cells() {
        int n = xaxis.nbins + 2;
        if (dimension > 1) {
            n *= yaxis.nbins + 2;
        }
        if (dimension > 2) {
            n *= zaxis.nbins + 2;
        }
        return n;
    }

    /** TH1::GetBin. */
    public final int bin(int binx, int biny, int binz) {
        final int nx = xaxis.nbins + 2;
        if (dimension == 1) {
            return binx;
        }
        if (dimension == 2) {
            return binx + nx * biny;
        }
        return binx + nx * (biny + (yaxis.nbins + 2) * binz);
    }

    /** TH1::Sumw2: from now on the squared weights are kept (filled from the contents so far). */
    public void sumw2() {
        if (sumw2.length == array.length) {
            return;
        }
        sumw2 = new double[array.length];
        if (entries > 0) {
            for (int i = 0; i < array.length; i++) {
                sumw2[i] = Math.abs(content(i));
            }
        }
    }

    // ---- filling -------------------------------------------------------------

    /** TH1::Fill(x, w). */
    public int fill(double x, double w) {
        entries++;
        final int bin = xaxis.findBin(x);
        if (bin < 0) {
            return -1;
        }
        if (sumw2.length == 0 && w != 1.0) {
            sumw2();
        }
        if (sumw2.length > 0) {
            sumw2[bin] += w * w;
        }
        array[bin] += w;
        if (bin == 0 || bin > xaxis.nbins) {
            if (!statOverflows) {
                return -1;
            }
        }
        final double z = w;
        tsumw += z;
        tsumw2 += z * z;
        tsumwx += z * x;
        tsumwx2 += z * x * x;
        return bin;
    }

    /** TH2::Fill(x, y, w). */
    public int fill(double x, double y, double w) {
        entries++;
        final int binx = xaxis.findBin(x);
        final int biny = yaxis.findBin(y);
        if (binx < 0 || biny < 0) {
            return -1;
        }
        final int bin = biny * (xaxis.nbins + 2) + binx;
        if (sumw2.length == 0 && w != 1.0) {
            sumw2();
        }
        if (sumw2.length > 0) {
            sumw2[bin] += w * w;
        }
        array[bin] += w;
        if (binx == 0 || binx > xaxis.nbins) {
            if (!statOverflows) {
                return -1;
            }
        }
        if (biny == 0 || biny > yaxis.nbins) {
            if (!statOverflows) {
                return -1;
            }
        }
        final double z = w;
        tsumw += z;
        tsumw2 += z * z;
        tsumwx += z * x;
        tsumwx2 += z * x * x;
        tsumwy += z * y;
        tsumwy2 += z * y * y;
        tsumwxy += z * x * y;
        return bin;
    }

    /** TH3::Fill(x, y, z, w). */
    public int fill(double x, double y, double z, double w) {
        entries++;
        final int binx = xaxis.findBin(x);
        final int biny = yaxis.findBin(y);
        final int binz = zaxis.findBin(z);
        if (binx < 0 || biny < 0 || binz < 0) {
            return -1;
        }
        final int bin = binx + (xaxis.nbins + 2) * (biny + (yaxis.nbins + 2) * binz);
        if (sumw2.length == 0 && w != 1.0) {
            sumw2();
        }
        if (sumw2.length > 0) {
            sumw2[bin] += w * w;
        }
        array[bin] += w;
        if (binx == 0 || binx > xaxis.nbins) {
            if (!statOverflows) {
                return -1;
            }
        }
        if (biny == 0 || biny > yaxis.nbins) {
            if (!statOverflows) {
                return -1;
            }
        }
        if (binz == 0 || binz > zaxis.nbins) {
            if (!statOverflows) {
                return -1;
            }
        }
        tsumw += w;
        tsumw2 += w * w;
        tsumwx += w * x;
        tsumwx2 += w * x * x;
        tsumwy += w * y;
        tsumwy2 += w * y * y;
        tsumwxy += w * x * y;
        tsumwz += w * z;
        tsumwz2 += w * z * z;
        tsumwxz += w * x * z;
        tsumwyz += w * y * z;
        return bin;
    }

    // ---- reading the cells ------------------------------------------------------

    /** RetrieveBinContent: the value of a cell (a profile overrides it with the mean). */
    public double content(int bin) {
        return array[bin];
    }

    /** GetBinErrorSqUnchecked: sumw2 when it is kept, the content otherwise. */
    public double errorSq(int bin) {
        return sumw2.length > 0 ? sumw2[bin] : content(bin);
    }

    /** TH1::GetBinError. */
    public double error(int bin) {
        if (bin < 0 || bin >= array.length) {
            return 0;
        }
        if (sumw2.length > 0) {
            return Math.sqrt(sumw2[bin]);
        }
        return Math.sqrt(Math.abs(content(bin)));
    }

    // ---- statistics ---------------------------------------------------------------

    /** The number of entries of TH1::GetStats for this kind of histogram. */
    public int statCount() {
        return dimension == 1 ? 4 : dimension == 2 ? 7 : 11;
    }

    /**
     * TH1::GetStats (TH2's, TH3's): the stored sums, or sums recomputed from
     * the cells when the stored ones were reset (fTsumw == 0 with entries).
     */
    public double[] stats() {
        final double[] s = new double[13];
        if (tsumw == 0 && entries > 0) {
            recomputeStats(s);
            return s;
        }
        s[0] = tsumw;
        s[1] = tsumw2;
        s[2] = tsumwx;
        s[3] = tsumwx2;
        if (dimension > 1) {
            s[4] = tsumwy;
            s[5] = tsumwy2;
            s[6] = tsumwxy;
        }
        if (dimension > 2) {
            s[7] = tsumwz;
            s[8] = tsumwz2;
            s[9] = tsumwxz;
            s[10] = tsumwyz;
        }
        return s;
    }

    private void recomputeStats(double[] s) {
        final int firstX = statOverflows ? 0 : 1;
        final int lastX = xaxis.nbins + (statOverflows ? 1 : 0);
        final int firstY = dimension > 1 ? firstX : 0;
        final int lastY = dimension > 1 ? yaxis.nbins + (statOverflows ? 1 : 0) : 0;
        final int firstZ = dimension > 2 ? firstX : 0;
        final int lastZ = dimension > 2 ? zaxis.nbins + (statOverflows ? 1 : 0) : 0;
        for (int k = firstZ; k <= lastZ; k++) {
            final double z = zaxis.binCenter(k);
            for (int j = firstY; j <= lastY; j++) {
                final double y = yaxis.binCenter(j);
                for (int i = firstX; i <= lastX; i++) {
                    final int bin = bin(i, j, k);
                    final double x = xaxis.binCenter(i);
                    final double w = content(bin);
                    final double err = Math.abs(error(bin));
                    s[0] += w;
                    s[1] += err * err;
                    s[2] += w * x;
                    s[3] += w * x * x;
                    if (dimension > 1) {
                        s[4] += w * y;
                        s[5] += w * y * y;
                        s[6] += w * x * y;
                    }
                    if (dimension > 2) {
                        s[7] += w * z;
                        s[8] += w * z * z;
                        s[9] += w * x * z;
                        s[10] += w * y * z;
                    }
                }
            }
        }
    }

    private static final int[] AXIS_INDEX = {2, 4, 7};

    /** TH1::GetMean(axis), axis 1 to 3. */
    public double mean(int axis) {
        if (axis < 1 || axis > 3) {
            return 0;
        }
        final double[] s = stats();
        if (s[0] == 0) {
            return 0;
        }
        return s[AXIS_INDEX[axis - 1]] / s[0];
    }

    /** TH1::GetStdDev(axis), axis 1 to 3. */
    public double stdDev(int axis) {
        if (axis < 1 || axis > 3) {
            return 0;
        }
        final double[] s = stats();
        if (s[0] == 0) {
            return 0;
        }
        final int axm = AXIS_INDEX[axis - 1];
        final double x = s[axm] / s[0];
        final double stddev2 = Math.max(s[axm + 1] / s[0] - x * x, 0.0);
        return Math.sqrt(stddev2);
    }

    /** TH1::GetSumOfAllWeights(includeOverflow). */
    public double sumOfAllWeights(boolean includeOverflow) {
        final int start = includeOverflow ? 0 : 1;
        final int lastX = xaxis.nbins + (includeOverflow ? 1 : 0);
        final int lastY = dimension > 1 ? yaxis.nbins + (includeOverflow ? 1 : 0) : start;
        final int lastZ = dimension > 2 ? zaxis.nbins + (includeOverflow ? 1 : 0) : start;
        double sum = 0;
        for (int binz = start; binz <= lastZ; binz++) {
            for (int biny = start; biny <= lastY; biny++) {
                for (int binx = start; binx <= lastX; binx++) {
                    sum += content(bin(binx, biny, binz));
                }
            }
        }
        return sum;
    }

    /** TH1::GetSumOfWeights: the in-range cells. */
    public double sumOfWeights() {
        return sumOfAllWeights(false);
    }

    /** TH1::GetEffectiveEntries: (sum w)^2 / sum w^2. */
    public double effectiveEntries() {
        final double[] s = stats();
        return s[1] != 0 ? s[0] * s[0] / s[1] : Math.abs(s[0]);
    }

    public boolean isProfile() {
        return false;
    }

    // ---- from a file -------------------------------------------------------------------

    /**
     * A histogram read by Sphere's object reader (any TH1, TH2, TH3 or
     * TProfile class): its axes, cells and sums, in double precision
     * whatever precision the class stores.
     */
    public static LabHist fromObject(RObject o) {
        final String cls = o.className;
        if (cls.startsWith("TProfile")) {
            return LabProfile.fromObject(o);
        }
        final int dim = cls.startsWith("TH3") ? 3 : cls.startsWith("TH2") ? 2 : 1;
        final LabAxis x = LabAxis.of(o.object("fXaxis"));
        final LabAxis y = dim > 1 ? LabAxis.of(o.object("fYaxis")) : null;
        final LabAxis z = dim > 2 ? LabAxis.of(o.object("fZaxis")) : null;
        final LabHist h = new LabHist(cls, o.string("fName"), o.string("fTitle"), x, y, z);
        fillCommon(h, o);
        return h;
    }

    static void fillCommon(LabHist h, RObject o) {
        final double[] values = doubles(o.get("fArray"));
        System.arraycopy(values, 0, h.array, 0, Math.min(values.length, h.array.length));
        final double[] w2 = doubles(o.get("fSumw2"));
        if (w2.length == h.array.length) {
            h.sumw2 = w2;
        }
        h.entries = o.real("fEntries");
        h.tsumw = o.real("fTsumw");
        h.tsumw2 = o.real("fTsumw2");
        h.tsumwx = o.real("fTsumwx");
        h.tsumwx2 = o.real("fTsumwx2");
        h.tsumwy = o.real("fTsumwy");
        h.tsumwy2 = o.real("fTsumwy2");
        h.tsumwxy = o.real("fTsumwxy");
        h.tsumwz = o.real("fTsumwz");
        h.tsumwz2 = o.real("fTsumwz2");
        h.tsumwxz = o.real("fTsumwxz");
        h.tsumwyz = o.real("fTsumwyz");
    }

    /** Any primitive array as doubles (TArrayF, TArrayI, ... come back as their own type). */
    static double[] doubles(Object v) {
        if (v instanceof double[] d) {
            return d;
        }
        if (v instanceof float[] f) {
            final double[] out = new double[f.length];
            for (int i = 0; i < f.length; i++) {
                out[i] = f[i];
            }
            return out;
        }
        if (v instanceof int[] a) {
            final double[] out = new double[a.length];
            for (int i = 0; i < a.length; i++) {
                out[i] = a[i];
            }
            return out;
        }
        if (v instanceof short[] a) {
            final double[] out = new double[a.length];
            for (int i = 0; i < a.length; i++) {
                out[i] = a[i];
            }
            return out;
        }
        if (v instanceof byte[] a) {
            final double[] out = new double[a.length];
            for (int i = 0; i < a.length; i++) {
                out[i] = a[i];
            }
            return out;
        }
        if (v instanceof long[] a) {
            final double[] out = new double[a.length];
            for (int i = 0; i < a.length; i++) {
                out[i] = a[i];
            }
            return out;
        }
        return new double[0];
    }

    @Override
    public String toString() {
        return className + " " + name + " (" + xaxis + (dimension > 1 ? " x " + yaxis : "")
             + (dimension > 2 ? " x " + zaxis : "") + ", " + entries + " entries)";
    }
}
