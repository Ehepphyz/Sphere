package com.sphere.core.rootlab.hist;

import com.sphere.core.rootio.RObject;

/**
 * TProfile, TProfile2D and TProfile3D: per cell the sum of weights
 * (fBinEntries), of w*v and w*v*v (the histogram's fArray and fSumw2), and of
 * w*w (fBinSumw2, kept only after Sumw2), v being y, z or t. Fill and the
 * error of a cell follow TProfile*.cxx and TProfileHelper::GetBinError.
 */
public final class LabProfile extends LabHist {

    public double[] binEntries;
    public double[] binSumw2 = new double[0];
    /** 0 error of the mean, 1 spread ("s"), 2 spread of integers ("i"), 3 gaussian ("g"). */
    public int errorMode;
    /** The range the profiled value must fall in; ignored when vmin == vmax. */
    public double vmin;
    public double vmax;
    /** Sum of w*v and w*v*v over all fills (fTsumwy, fTsumwz or fTsumwt). */
    public double tsumwv;
    public double tsumwv2;
    /** TProfile*::Approximate: estimate a cell's spread from the whole profile when its own is zero. */
    public boolean approximate;

    public LabProfile(String className, String name, String title, LabAxis x, LabAxis y, LabAxis z) {
        super(className, name, title, x, y, z);
        this.binEntries = new double[array.length];
        this.sumw2 = new double[array.length];          // a profile always keeps sum of w*v*v
    }

    public static LabProfile tprofile(String name, String title, int nbins, double min, double max) {
        return new LabProfile("TProfile", name, title, new LabAxis(nbins, min, max), null, null);
    }

    public static LabProfile tprofile2d(String name, String title, int nx, double xmin, double xmax,
                                        int ny, double ymin, double ymax) {
        return new LabProfile("TProfile2D", name, title, new LabAxis(nx, xmin, xmax),
                              new LabAxis(ny, ymin, ymax), null);
    }

    public static LabProfile tprofile3d(String name, String title, int nx, double xmin, double xmax,
                                        int ny, double ymin, double ymax, int nz, double zmin, double zmax) {
        return new LabProfile("TProfile3D", name, title, new LabAxis(nx, xmin, xmax),
                              new LabAxis(ny, ymin, ymax), new LabAxis(nz, zmin, zmax));
    }

    @Override
    public boolean isProfile() {
        return true;
    }

    /** Sumw2 for a profile: keep the sum of squared weights per cell. */
    @Override
    public void sumw2() {
        if (binSumw2.length == array.length) {
            return;
        }
        binSumw2 = new double[array.length];
        if (entries > 0) {
            System.arraycopy(binEntries, 0, binSumw2, 0, binEntries.length);
        }
    }

    // ---- filling ------------------------------------------------------------

    /** TProfile::Fill(x, y, w). */
    public int fillProfile(double x, double y, double w) {
        if (vmin != vmax) {
            if (y < vmin || y > vmax || Double.isNaN(y)) {
                return -1;
            }
        }
        final double u = w;
        entries++;
        final int bin = xaxis.findBin(x);
        array[bin] += u * y;
        sumw2[bin] += u * y * y;
        if (binSumw2.length == 0 && u != 1.0) {
            sumw2();
        }
        if (binSumw2.length > 0) {
            binSumw2[bin] += u * u;
        }
        binEntries[bin] += u;
        if (bin == 0 || bin > xaxis.nbins) {
            if (!statOverflows) {
                return -1;
            }
        }
        tsumw += u;
        tsumw2 += u * u;
        tsumwx += u * x;
        tsumwx2 += u * x * x;
        tsumwv += u * y;
        tsumwv2 += u * y * y;
        return bin;
    }

    /** TProfile2D::Fill(x, y, z, w). */
    public int fillProfile(double x, double y, double z, double w) {
        if (vmin != vmax) {
            if (z < vmin || z > vmax || Double.isNaN(z)) {
                return -1;
            }
        }
        final double u = w;
        entries++;
        final int binx = xaxis.findBin(x);
        final int biny = yaxis.findBin(y);
        if (binx < 0 || biny < 0) {
            return -1;
        }
        final int bin = biny * (xaxis.nbins + 2) + binx;
        array[bin] += u * z;
        sumw2[bin] += u * z * z;
        if (binSumw2.length == 0 && u != 1.0) {
            sumw2();
        }
        if (binSumw2.length > 0) {
            binSumw2[bin] += u * u;
        }
        binEntries[bin] += u;
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
        tsumw += u;
        tsumw2 += u * u;
        tsumwx += u * x;
        tsumwx2 += u * x * x;
        tsumwy += u * y;
        tsumwy2 += u * y * y;
        tsumwxy += u * x * y;
        tsumwv += u * z;
        tsumwv2 += u * z * z;
        return bin;
    }

    /** TProfile3D::Fill(x, y, z, t, w). */
    public int fillProfile(double x, double y, double z, double t, double w) {
        if (vmin != vmax) {
            if (t < vmin || t > vmax || Double.isNaN(t)) {
                return -1;
            }
        }
        final double u = w;
        entries++;
        final int binx = xaxis.findBin(x);
        final int biny = yaxis.findBin(y);
        final int binz = zaxis.findBin(z);
        if (binx < 0 || biny < 0 || binz < 0) {
            return -1;
        }
        final int bin = bin(binx, biny, binz);
        array[bin] += u * t;
        sumw2[bin] += u * t * t;
        if (binSumw2.length == 0 && u != 1.0) {
            sumw2();
        }
        if (binSumw2.length > 0) {
            binSumw2[bin] += u * u;
        }
        binEntries[bin] += u;
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
        tsumw += u;
        tsumw2 += u * u;
        tsumwx += u * x;
        tsumwx2 += u * x * x;
        tsumwy += u * y;
        tsumwy2 += u * y * y;
        tsumwxy += u * x * y;
        tsumwz += u * z;
        tsumwz2 += u * z * z;
        tsumwxz += u * x * z;
        tsumwyz += u * y * z;
        tsumwv += u * t;
        tsumwv2 += u * t * t;
        return bin;
    }

    // ---- reading -------------------------------------------------------------------

    /** TProfile::RetrieveBinContent: the mean of the cell. */
    @Override
    public double content(int bin) {
        return binEntries[bin] > 0 ? array[bin] / binEntries[bin] : 0;
    }

    @Override
    public double errorSq(int bin) {
        final double e = error(bin);
        return e * e;
    }

    /** TProfileHelper::GetBinEffectiveEntries. */
    public double effectiveEntries(int bin) {
        final double sumOfWeights = binEntries[bin];
        if (binSumw2.length == 0 || binSumw2.length != array.length) {
            return sumOfWeights;
        }
        final double sumOfWeightsSquare = binSumw2[bin];
        return sumOfWeightsSquare > 0 ? sumOfWeights * sumOfWeights / sumOfWeightsSquare : 0;
    }

    /** TProfileHelper::GetBinError. */
    @Override
    public double error(int bin) {
        if (bin < 0 || bin >= array.length) {
            return 0;
        }
        final double cont = array[bin];
        double sum = binEntries[bin];
        final double err2 = sumw2[bin];
        final double neff = effectiveEntries(bin);
        if (sum == 0) {
            return 0;
        }
        if (errorMode == 3) {
            return 1. / Math.sqrt(sum);
        }
        final double contsum = cont / sum;
        final double eprim2 = Math.abs(err2 / sum - contsum * contsum);
        double eprim = Math.sqrt(eprim2);
        if (errorMode == 2) {
            if (eprim != 0) {
                return eprim / Math.sqrt(neff);
            }
            return 1 / Math.sqrt(12 * neff);
        }
        double test = 1;
        if (err2 != 0 && neff < 5) {
            test = eprim2 * sum / err2;
        }
        if (approximate && (test < 1.e-4 || eprim2 <= 0)) {
            final double[] s = stats();
            final double ssum = s[0];
            final int index = dimension == 1 ? 4 : dimension == 2 ? 7 : 11;
            final double scont = s[index];
            final double serr2 = s[index + 1];
            final double scontsum = scont / ssum;
            final double seprim2 = Math.abs(serr2 / ssum - scontsum * scontsum);
            eprim = 2 * Math.sqrt(seprim2);
            sum = ssum;
        }
        sum = Math.abs(sum);
        if (errorMode == 1) {
            return eprim;
        }
        return eprim / Math.sqrt(neff);
    }

    @Override
    public int statCount() {
        return dimension == 1 ? 6 : dimension == 2 ? 9 : 13;
    }

    /** TProfile*::GetStats: the histogram's sums, then those of the profiled value. */
    @Override
    public double[] stats() {
        final double[] s = new double[13];
        final int index = dimension == 1 ? 4 : dimension == 2 ? 7 : 11;
        if (tsumw == 0) {
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
                        final double w = binEntries[bin];
                        final double w2 = binSumw2.length > 0 ? binSumw2[bin] : w;
                        final double x = xaxis.binCenter(i);
                        s[0] += w;
                        s[1] += w2;
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
                        s[index] += array[bin];
                        s[index + 1] += sumw2[bin];
                    }
                }
            }
            return s;
        }
        if (tsumwv == 0 && tsumwv2 == 0) {
            // Files that never stored them: GetStats rebuilds them from the
            // cells in range, and keeps them.
            for (int k = dimension > 2 ? 1 : 0; k <= (dimension > 2 ? zaxis.nbins : 0); k++) {
                for (int j = dimension > 1 ? 1 : 0; j <= (dimension > 1 ? yaxis.nbins : 0); j++) {
                    for (int i = 1; i <= xaxis.nbins; i++) {
                        final int bin = bin(i, j, k);
                        tsumwv += array[bin];
                        tsumwv2 += sumw2[bin];
                    }
                }
            }
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
        s[index] = tsumwv;
        s[index + 1] = tsumwv2;
        return s;
    }

    // ---- from a file -------------------------------------------------------------------

    public static LabProfile fromObject(RObject o) {
        final String cls = o.className;
        final int dim = cls.startsWith("TProfile3D") ? 3 : cls.startsWith("TProfile2D") ? 2 : 1;
        final LabAxis x = LabAxis.of(o.object("fXaxis"));
        final LabAxis y = dim > 1 ? LabAxis.of(o.object("fYaxis")) : null;
        final LabAxis z = dim > 2 ? LabAxis.of(o.object("fZaxis")) : null;
        final LabProfile p = new LabProfile(cls, o.string("fName"), o.string("fTitle"), x, y, z);
        fillCommon(p, o);
        if (p.sumw2.length != p.array.length) {
            p.sumw2 = new double[p.array.length];
        }
        final double[] entries = doubles(o.get("fBinEntries"));
        System.arraycopy(entries, 0, p.binEntries, 0, Math.min(entries.length, p.binEntries.length));
        final double[] bs = doubles(o.get("fBinSumw2"));
        if (bs.length == p.array.length) {
            p.binSumw2 = bs;
        }
        p.errorMode = o.integer("fErrorMode");
        final String v = dim == 1 ? "Y" : dim == 2 ? "Z" : "T";
        p.vmin = o.real("f" + v + "min");
        p.vmax = o.real("f" + v + "max");
        p.tsumwv = o.real("fTsumw" + v.toLowerCase());
        p.tsumwv2 = o.real("fTsumw" + v.toLowerCase() + "2");
        if (dim == 1) {
            // a 1D profile has no y statistics of its own: those sums are the profiled value's
            p.tsumwy = 0;
            p.tsumwy2 = 0;
        }
        return p;
    }
}
