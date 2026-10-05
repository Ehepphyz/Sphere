package com.sphere.components.rootview;

import java.util.Arrays;

/**
 * ROOT's algorithms behind the functions of a histogram's menu, carried over
 * from ROOT's sources line for line so that they give ROOT's numbers:
 *
 *   TH1::SmoothArray       353QH twice, the running medians of TH1::Smooth
 *   TH2::Smooth            the k5a, k5b and k3a kernels
 *   TSpectrum::Background  SNIP clipping, of order 2 or 4, smoothed or not
 *   TSpectrum::SearchHighRes  peaks by Markov smoothing, SNIP background
 *                          removal and Gold deconvolution, as ShowPeaks finds them
 */
public final class RootSpectrum {

    /** TSpectrum's default: as many peaks as these at most. */
    public static final int MAX_PEAKS = 100;
    private static final int PEAK_WINDOW = 1024;
    /** TSpectrum's fgIterations and fgAverageWindow. */
    private static final int DECON_ITERATIONS = 3;
    private static final int AVERAGE_WINDOW = 3;

    private RootSpectrum() {
    }

    /* ------------------------------------------------------------------ */
    /* TH1::SmoothArray                                                    */
    /* ------------------------------------------------------------------ */

    private static double median(double[] a, int from, int n) {
        final double[] c = Arrays.copyOfRange(a, from, from + n);
        Arrays.sort(c);
        return n % 2 == 1 ? c[n / 2] : 0.5 * (c[n / 2 - 1] + c[n / 2]);
    }

    /** Smooths xx in place, ntimes times, by ROOT's 353QH twice. Needs three points at least. */
    public static void smoothArray(double[] xx, int ntimes) {
        final int nn = xx.length;
        if (nn < 3) return;
        final double[] hh = new double[3];
        final double[] yy = new double[nn];
        final double[] zz = new double[nn];
        final double[] rr = new double[nn];
        for (int pass = 0; pass < ntimes; pass++) {
            System.arraycopy(xx, 0, zz, 0, nn);
            for (int noent = 0; noent < 2; ++noent) {
                for (int kk = 0; kk < 3; kk++) {
                    System.arraycopy(zz, 0, yy, 0, nn);
                    final int medianType = kk != 1 ? 3 : 5;
                    final int ifirst = kk != 1 ? 1 : 2;
                    final int ilast = kk != 1 ? nn - 1 : nn - 2;
                    for (int ii = ifirst; ii < ilast; ii++) zz[ii] = median(yy, ii - ifirst, medianType);
                    if (kk == 0) {
                        hh[0] = zz[1];
                        hh[1] = zz[0];
                        hh[2] = 3 * zz[1] - 2 * zz[2];
                        zz[0] = median(hh, 0, 3);
                        hh[0] = zz[nn - 2];
                        hh[1] = zz[nn - 1];
                        hh[2] = 3 * zz[nn - 2] - 2 * zz[nn - 3];
                        zz[nn - 1] = median(hh, 0, 3);
                    }
                    if (kk == 1) {
                        zz[1] = median(yy, 0, 3);
                        zz[nn - 2] = median(yy, nn - 3, 3);
                    }
                }
                System.arraycopy(zz, 0, yy, 0, nn);
                for (int ii = 2; ii < nn - 2; ii++) {
                    if (zz[ii - 1] != zz[ii]) continue;
                    if (zz[ii] != zz[ii + 1]) continue;
                    final double tmp0 = zz[ii - 2] - zz[ii];
                    final double tmp1 = zz[ii + 2] - zz[ii];
                    if (tmp0 * tmp1 <= 0) continue;
                    int jk = 1;
                    if (Math.abs(tmp1) > Math.abs(tmp0)) jk = -1;
                    yy[ii] = -0.5 * zz[ii - 2 * jk] + zz[ii] / 0.75 + zz[ii + 2 * jk] / 6.;
                    yy[ii + jk] = 0.5 * (zz[ii + 2 * jk] - zz[ii - 2 * jk]) + zz[ii];
                }
                for (int ii = 1; ii < nn - 1; ii++) zz[ii] = 0.25 * yy[ii - 1] + 0.5 * yy[ii] + 0.25 * yy[ii + 1];
                zz[0] = yy[0];
                zz[nn - 1] = yy[nn - 1];
                if (noent == 0) {
                    System.arraycopy(zz, 0, rr, 0, nn);
                    for (int ii = 0; ii < nn; ii++) zz[ii] = xx[ii] - zz[ii];
                }
            }
            double xmin = Double.MAX_VALUE;
            for (double v : xx) xmin = Math.min(xmin, v);
            for (int ii = 0; ii < nn; ii++) {
                xx[ii] = xmin < 0 ? rr[ii] + zz[ii] : Math.max(rr[ii] + zz[ii], 0.0);
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* TH2::Smooth                                                         */
    /* ------------------------------------------------------------------ */

    private static final double[][] K5A = {{0, 0, 1, 0, 0}, {0, 2, 2, 2, 0}, {1, 2, 5, 2, 1}, {0, 2, 2, 2, 0}, {0, 0, 1, 0, 0}};
    private static final double[][] K5B = {{0, 1, 2, 1, 0}, {1, 2, 4, 2, 1}, {2, 4, 8, 4, 2}, {1, 2, 4, 2, 1}, {0, 1, 2, 1, 0}};
    private static final double[][] K3A = {{0, 1, 0}, {1, 2, 1}, {0, 1, 0}};

    /**
     * TH2::Smooth: each bin replaced by the kernel's weighted mean of its
     * neighbours. v holds nx * ny contents, x fastest; err may be null.
     */
    public static void smooth2D(double[] v, double[] err, int nx, int ny, String option) {
        final String o = option == null ? "" : option.toLowerCase(java.util.Locale.ROOT);
        final double[][] kernel = o.contains("k3a") ? K3A : o.contains("k5b") ? K5B : K5A;
        final int ks = kernel.length;
        final int push = (ks - 1) / 2;
        final double[] buf = v.clone();
        final double[] ebuf = err == null ? null : err.clone();
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                double content = 0;
                double error = 0;
                double norm = 0;
                for (int n = 0; n < ks; n++) {
                    for (int m = 0; m < ks; m++) {
                        final int xb = i + (n - push);
                        final int yb = j + (m - push);
                        if (xb < 0 || xb >= nx || yb < 0 || yb >= ny) continue;
                        final double k = kernel[n][m];
                        if (k == 0) continue;
                        norm += k;
                        content += k * buf[yb * nx + xb];
                        if (ebuf != null) error += k * k * ebuf[yb * nx + xb] * ebuf[yb * nx + xb];
                    }
                }
                if (norm != 0) {
                    v[j * nx + i] = content / norm;
                    if (err != null) err[j * nx + i] = Math.sqrt(error / (norm * norm));
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* TSpectrum::Background                                               */
    /* ------------------------------------------------------------------ */

    /**
     * The background under a spectrum, by SNIP: the window clipped from
     * niter down to 1 (or up, "BackIncreasingWindow"), of order 2 or 4
     * ("BackOrder4"), each point compared with the mean of its smoothed
     * neighbours unless "nosmoothing", in a window of 3 to 15 ("BackSmoothing5"...).
     */
    public static double[] background(double[] spectrum, int niter, String option) {
        final String o = option == null ? "" : option.toLowerCase(java.util.Locale.ROOT);
        final int ssize = spectrum.length;
        final boolean increasing = o.contains("backincreasingwindow");
        final boolean order4 = o.contains("backorder4") || o.contains("backorder6") || o.contains("backorder8");
        final boolean smoothing = !o.contains("nosmoothing");
        int smoothWindow = 3;
        for (int w : new int[]{5, 7, 9, 11, 13, 15}) if (o.contains("backsmoothing" + w)) smoothWindow = w;
        final double[] out = spectrum.clone();
        if (ssize < 2 * niter + 1 || niter < 1) return out;
        final double[] ws = new double[2 * ssize];
        for (int i = 0; i < ssize; i++) {
            ws[i] = spectrum[i];
            ws[i + ssize] = spectrum[i];
        }
        final int bw = (smoothWindow - 1) / 2;
        int i = increasing ? 1 : niter;
        do {
            for (int j = i; j < ssize - i; j++) {
                double a = ws[ssize + j];
                if (!smoothing) {
                    double b = (ws[ssize + j - i] + ws[ssize + j + i]) / 2.0;
                    if (order4) {
                        final int ai = i / 2;
                        double c = -ws[ssize + j - 2 * ai] / 6 + 4 * ws[ssize + j - ai] / 6 + 4 * ws[ssize + j + ai] / 6
                            - ws[ssize + j + 2 * ai] / 6;
                        if (b < c) b = c;
                    }
                    if (b < a) a = b;
                    ws[j] = a;
                } else {
                    double av = mean(ws, ssize, j - bw, j + bw);
                    double b = mean(ws, ssize, j - i - bw, j - i + bw);
                    final double c = mean(ws, ssize, j + i - bw, j + i + bw);
                    b = (b + c) / 2;
                    if (order4) {
                        final int ai = i / 2;
                        final double b4 = -mean(ws, ssize, j - 2 * ai - bw, j - 2 * ai + bw) / 6
                            + 4 * mean(ws, ssize, j - ai - bw, j - ai + bw) / 6
                            + 4 * mean(ws, ssize, j + ai - bw, j + ai + bw) / 6
                            - mean(ws, ssize, j + 2 * ai - bw, j + 2 * ai + bw) / 6;
                        if (b < b4) b = b4;
                    }
                    if (b < a) av = b;
                    ws[j] = av;
                }
            }
            for (int j = i; j < ssize - i; j++) ws[ssize + j] = ws[j];
            i += increasing ? 1 : -1;
        } while (increasing ? i <= niter : i >= 1);
        System.arraycopy(ws, ssize, out, 0, ssize);
        return out;
    }

    private static double mean(double[] ws, int ssize, int from, int to) {
        double s = 0;
        int n = 0;
        for (int w = from; w <= to; w++) {
            if (w >= 0 && w < ssize) {
                s += ws[ssize + w];
                n++;
            }
        }
        return n == 0 ? 0 : s / n;
    }

    /* ------------------------------------------------------------------ */
    /* TSpectrum::SearchHighRes                                            */
    /* ------------------------------------------------------------------ */

    /** The peaks found: positions in bins (fractional, from 0), and the spectrum deconvolved. */
    public record Peaks(double[] positions, double[] deconvolved) {
    }

    /**
     * TSpectrum::Search's engine: sigma the expected width in bins, threshold
     * the smallest peak kept in percent of the highest, background removed
     * by SNIP unless told not, Markov smoothing unless told not, then Gold's
     * deconvolution, and local maxima of the result. The positions are
     * sorted from the highest peak down, as ROOT gives them.
     */
    public static Peaks searchHighRes(double[] source, double sigma, double threshold, boolean backgroundRemove,
                                      boolean markov) {
        final int ssize = source.length;
        final int numberIterations = (int) (7 * sigma + 0.5);
        final int sizeExt = ssize + 2 * numberIterations;
        final int shift = numberIterations;
        final int bw = 2;
        if (sigma < 1 || threshold <= 0 || threshold >= 100) return new Peaks(new double[0], new double[ssize]);
        if ((int) (5.0 * sigma + 0.5) >= PEAK_WINDOW / 2) return new Peaks(new double[0], new double[ssize]);
        if (backgroundRemove && ssize < 2 * numberIterations + 1) backgroundRemove = false;
        double m0low = 0;
        double m1low = 0;
        double m2low = 0;
        double l0low = 0;
        double l1low = 0;
        int k = (int) (2 * sigma + 0.5);
        if (k >= 2) {
            for (int i = 0; i < k && i < ssize; i++) {
                final double a = i;
                final double b = source[i];
                m0low += 1;
                m1low += a;
                m2low += a * a;
                l0low += b;
                l1low += a * b;
            }
            final double detlow = m0low * m2low - m1low * m1low;
            l1low = detlow != 0 ? (-l0low * m1low + l1low * m0low) / detlow : 0;
            if (l1low > 0) l1low = 0;
        } else {
            l1low = 0;
        }
        final int extra = 2 * (int) (7 * sigma + 0.5);
        final double[] ws = new double[7 * (ssize + extra)];
        for (int i = 0; i < sizeExt; i++) {
            if (i < shift) {
                ws[i + sizeExt] = Math.max(0, source[0] + l1low * (i - shift));
            } else if (i >= ssize + shift) {
                ws[i + sizeExt] = Math.max(0, source[ssize - 1]);
            } else {
                ws[i + sizeExt] = source[i - shift];
            }
        }
        if (backgroundRemove) {
            for (int i = 1; i <= numberIterations; i++) {
                for (int j = i; j < sizeExt - i; j++) {
                    if (!markov) {
                        double a = ws[sizeExt + j];
                        final double b = (ws[sizeExt + j - i] + ws[sizeExt + j + i]) / 2.0;
                        if (b < a) a = b;
                        ws[j] = a;
                    } else {
                        final double a = ws[sizeExt + j];
                        double av = meanExt(ws, sizeExt, j - bw, j + bw);
                        double b = meanExt(ws, sizeExt, j - i - bw, j - i + bw);
                        final double c = meanExt(ws, sizeExt, j + i - bw, j + i + bw);
                        b = (b + c) / 2;
                        if (b < a) av = b;
                        ws[j] = av;
                    }
                }
                for (int j = i; j < sizeExt - i; j++) ws[sizeExt + j] = ws[j];
            }
            for (int j = 0; j < sizeExt; j++) {
                final double b;
                if (j < shift) b = Math.max(0, source[0] + l1low * (j - shift));
                else if (j >= ssize + shift) b = Math.max(0, source[ssize - 1]);
                else b = source[j - shift];
                ws[sizeExt + j] = b - ws[sizeExt + j];
            }
            for (int j = 0; j < sizeExt; j++) if (ws[sizeExt + j] < 0) ws[sizeExt + j] = 0;
        }
        for (int i = 0; i < sizeExt; i++) ws[i + 6 * sizeExt] = ws[i + sizeExt];
        if (markov) {
            for (int j = 0; j < sizeExt; j++) ws[2 * sizeExt + j] = ws[sizeExt + j];
            final int xmin = 0;
            final int xmax = sizeExt - 1;
            double maxch = 0;
            double plocha = 0;
            for (int i = 0; i < sizeExt; i++) {
                ws[i] = 0;
                if (maxch < ws[2 * sizeExt + i]) maxch = ws[2 * sizeExt + i];
                plocha += ws[2 * sizeExt + i];
            }
            if (maxch == 0) return new Peaks(new double[0], new double[ssize]);
            double nom = 1;
            ws[xmin] = 1;
            for (int i = xmin; i < xmax; i++) {
                final double nip = ws[2 * sizeExt + i] / maxch;
                final double nim = ws[2 * sizeExt + i + 1] / maxch;
                double sp = 0;
                double sm = 0;
                for (int l = 1; l <= AVERAGE_WINDOW; l++) {
                    double a = (i + l) > xmax ? ws[2 * sizeExt + xmax] / maxch : ws[2 * sizeExt + i + l] / maxch;
                    double b = a - nip;
                    a = a + nip <= 0 ? 1 : Math.sqrt(a + nip);
                    sp += Math.exp(b / a);
                    a = (i - l + 1) < xmin ? ws[2 * sizeExt + xmin] / maxch : ws[2 * sizeExt + i - l + 1] / maxch;
                    b = a - nim;
                    a = a + nim <= 0 ? 1 : Math.sqrt(a + nim);
                    sm += Math.exp(b / a);
                }
                final double a = ws[i + 1] = ws[i] * (sp / sm);
                nom += a;
            }
            for (int i = xmin; i <= xmax; i++) ws[i] /= nom;
            for (int j = 0; j < sizeExt; j++) ws[sizeExt + j] = ws[j] * plocha;
            for (int j = 0; j < sizeExt; j++) ws[2 * sizeExt + j] = ws[sizeExt + j];
            if (backgroundRemove) {
                for (int i = 1; i <= numberIterations; i++) {
                    for (int j = i; j < sizeExt - i; j++) {
                        double a = ws[sizeExt + j];
                        final double b = (ws[sizeExt + j - i] + ws[sizeExt + j + i]) / 2.0;
                        if (b < a) a = b;
                        ws[j] = a;
                    }
                    for (int j = i; j < sizeExt - i; j++) ws[sizeExt + j] = ws[j];
                }
                for (int j = 0; j < sizeExt; j++) ws[sizeExt + j] = ws[2 * sizeExt + j] - ws[sizeExt + j];
            }
        }
        double area = 0;
        int lhGold = -1;
        int posit = 0;
        double maximum = 0;
        for (int i = 0; i < sizeExt; i++) {
            double lda = i - 3 * sigma;
            lda = lda * lda / (2 * sigma * sigma);
            final int jj = (int) (1000 * Math.exp(-lda));
            lda = jj;
            if (lda != 0) lhGold = i + 1;
            ws[i] = lda;
            area += lda;
            if (lda > maximum) {
                maximum = lda;
                posit = i;
            }
        }
        for (int i = 0; i < sizeExt; i++) ws[2 * sizeExt + i] = Math.abs(ws[sizeExt + i]);
        int ii = Math.min(lhGold - 1, sizeExt);
        int imin = -ii;
        int imax = ii;
        for (int i = imin; i <= imax; i++) {
            double lda = 0;
            final int jmin = i < 0 ? -i : 0;
            final int jmax = Math.min(lhGold - 1 - i, lhGold - 1);
            for (int j = jmin; j <= jmax; j++) lda += ws[j] * ws[i + j];
            ws[sizeExt + i - imin] = lda;
        }
        ii = lhGold - 1;
        imin = -ii;
        imax = sizeExt + ii - 1;
        for (int i = imin; i <= imax; i++) {
            double lda = 0;
            for (int j = 0; j <= lhGold - 1; j++) {
                final int kk = i + j;
                if (kk >= 0 && kk < sizeExt) lda += ws[j] * ws[2 * sizeExt + kk];
            }
            ws[4 * sizeExt + i - imin] = lda;
        }
        for (int i = imin; i <= imax; i++) ws[2 * sizeExt + i - imin] = ws[4 * sizeExt + i - imin];
        for (int i = 0; i < sizeExt; i++) ws[i] = 1;
        for (int lindex = 0; lindex < DECON_ITERATIONS; lindex++) {
            for (int i = 0; i < sizeExt; i++) {
                if (Math.abs(ws[2 * sizeExt + i]) > 0.00001 && Math.abs(ws[i]) > 0.00001) {
                    double lda = 0;
                    int jmin = Math.min(lhGold - 1, i);
                    jmin = -jmin;
                    final int jmax = Math.min(lhGold - 1, sizeExt - 1 - i);
                    for (int j = jmin; j <= jmax; j++) lda += ws[j + lhGold - 1 + sizeExt] * ws[i + j];
                    double ldb = ws[2 * sizeExt + i];
                    lda = lda != 0 ? ldb / lda : 0;
                    ldb = ws[i];
                    ws[3 * sizeExt + i] = lda * ldb;
                }
            }
            for (int i = 0; i < sizeExt; i++) ws[i] = ws[3 * sizeExt + i];
        }
        for (int i = 0; i < sizeExt; i++) ws[sizeExt + (i + posit) % sizeExt] = ws[i];
        maximum = 0;
        double maximumDecon = 0;
        final int jshift = lhGold - 1;
        for (int i = 0; i < sizeExt - jshift; i++) {
            if (i >= shift && i < ssize + shift) {
                ws[i] = area * ws[sizeExt + i + jshift];
                if (maximumDecon < ws[i]) maximumDecon = ws[i];
                if (maximum < ws[6 * sizeExt + i]) maximum = ws[6 * sizeExt + i];
            } else {
                ws[i] = 0;
            }
        }
        double lda = Math.min(1, threshold) / 100;
        final double[] positions = new double[MAX_PEAKS];
        int peakIndex = 0;
        for (int i = 1; i < sizeExt - 1; i++) {
            if (!(ws[i] > ws[i - 1] && ws[i] > ws[i + 1])) continue;
            if (i < shift || i >= ssize + shift) continue;
            if (!(ws[i] > lda * maximumDecon && ws[6 * sizeExt + i] > threshold * maximum / 100.0)) continue;
            double a = 0;
            double b = 0;
            for (int j = i - 1; j <= i + 1; j++) {
                a += (double) (j - shift) * ws[j];
                b += ws[j];
            }
            a = a / b;
            if (a < 0) a = 0;
            if (a >= ssize) a = ssize - 1;
            if (peakIndex == 0) {
                positions[0] = a;
                peakIndex = 1;
            } else {
                int j;
                int priz = 0;
                for (j = 0; j < peakIndex && priz == 0; j++) {
                    if (ws[6 * sizeExt + shift + (int) a] > ws[6 * sizeExt + shift + (int) positions[j]]) priz = 1;
                }
                if (priz == 0) {
                    if (j < MAX_PEAKS) positions[j] = a;
                } else {
                    for (int kk = peakIndex; kk >= j; kk--) if (kk < MAX_PEAKS && kk >= 1) positions[kk] = positions[kk - 1];
                    positions[j - 1] = a;
                }
                if (peakIndex < MAX_PEAKS) peakIndex++;
            }
        }
        final double[] dest = new double[ssize];
        for (int i = 0; i < ssize; i++) dest[i] = ws[i + shift];
        return new Peaks(Arrays.copyOf(positions, peakIndex), dest);
    }

    private static double meanExt(double[] ws, int sizeExt, int from, int to) {
        double s = 0;
        int n = 0;
        for (int w = from; w <= to; w++) {
            if (w >= 0 && w < sizeExt) {
                s += ws[sizeExt + w];
                n++;
            }
        }
        return n == 0 ? 0 : s / n;
    }
}
