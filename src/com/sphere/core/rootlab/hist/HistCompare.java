package com.sphere.core.rootlab.hist;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Comparison of two histograms the way stressHistogram's equals() and
 * compareStatistics() do it -- every cell's centre, content and error within a
 * relative tolerance, then Chi2Test("WW OF UF") both ways, mean and standard
 * deviation on each axis, sums of weights, entries and effective entries --
 * with, in addition, a list of what differed, so a failed comparison says
 * where rather than only that it failed.
 *
 * Chi2Test and the probability behind it (TMath::Prob, through the
 * complemented incomplete gamma function of Cephes, as ROOT computes it) are
 * also what the ROOT Lab uses to compare two files histogram by histogram.
 */
public final class HistCompare {

    public static final int OPT_PRINT = 1;
    public static final int OPT_DEBUG = 2;
    public static final int OPT_NO_ERROR = 4;
    public static final int OPT_STATS = 8;

    /** The result: how many quantities differed, and the first few of them. */
    public static final class Outcome {
        public int differences;
        public final List<String> details = new ArrayList<>();

        void note(String what) {
            differences++;
            if (details.size() < 12) {
                details.add(what);
            }
        }

        public boolean ok() {
            return differences == 0;
        }

        @Override
        public String toString() {
            return ok() ? "identical" : differences + " difference(s): " + String.join("; ", details);
        }
    }

    private HistCompare() {
    }

    /** stressHistogram's equals(Double_t, Double_t, ERRORLIMIT): true when they differ. */
    public static boolean differ(double n1, double n2, double errorLimit) {
        if (n1 != 0) {
            return Math.abs(n1 - n2) > errorLimit * Math.abs(n1);
        }
        return Math.abs(n2) > errorLimit;
    }

    private static void check(Outcome out, String what, double a, double b, double limit) {
        if (differ(a, b, limit)) {
            out.note(String.format(Locale.ROOT, "%s %.12g vs %.12g", what, a, b));
        }
    }

    /** equals(msg, h1, h2, options, ERRORLIMIT) for 1D, 2D or 3D histograms and profiles. */
    public static Outcome equals(LabHist h1, LabHist h2, int options, double errorLimit) {
        final Outcome out = new Outcome();
        final boolean compareError = (options & OPT_NO_ERROR) == 0;
        if (h1 == h2) {
            out.note("both sides are the same object");
        }
        if (h1.dimension != h2.dimension) {
            out.note("dimensions " + h1.dimension + " vs " + h2.dimension);
            return out;
        }
        if (h1.dimension == 1) {
            check(out, "nbins", h1.xaxis.nbins, h2.xaxis.nbins, errorLimit);
            check(out, "xmin", h1.xaxis.xmin, h2.xaxis.xmin, errorLimit);
            check(out, "xmax", h1.xaxis.xmax, h2.xaxis.xmax, errorLimit);
            if (h1.xaxis.nbins != h2.xaxis.nbins) {
                return out;
            }
        } else if (h1.xaxis.nbins != h2.xaxis.nbins || h1.yaxis.nbins != h2.yaxis.nbins
                   || (h1.dimension == 3 && h1.zaxis.nbins != h2.zaxis.nbins)) {
            out.note("binning differs");
            return out;
        }
        final int ny = h1.dimension > 1 ? h1.yaxis.nbins + 1 : 0;
        final int nz = h1.dimension > 2 ? h1.zaxis.nbins + 1 : 0;
        for (int i = 0; i <= h1.xaxis.nbins + 1; ++i) {
            for (int j = 0; j <= ny; ++j) {
                for (int k = 0; k <= nz; ++k) {
                    final String cell = h1.dimension == 1 ? "bin " + i
                        : h1.dimension == 2 ? "bin (" + i + "," + j + ")" : "bin (" + i + "," + j + "," + k + ")";
                    check(out, cell + " x centre", h1.xaxis.binCenter(i), h2.xaxis.binCenter(i), errorLimit);
                    if (h1.dimension > 1) {
                        check(out, cell + " y centre", h1.yaxis.binCenter(j), h2.yaxis.binCenter(j), errorLimit);
                    }
                    if (h1.dimension > 2) {
                        check(out, cell + " z centre", h1.zaxis.binCenter(k), h2.zaxis.binCenter(k), errorLimit);
                    }
                    final int b = h1.bin(i, j, k);
                    check(out, cell + " content", h1.content(b), h2.content(b), errorLimit);
                    if (compareError) {
                        check(out, cell + " error", h1.error(b), h2.error(b), errorLimit);
                    }
                }
            }
        }
        if ((options & OPT_STATS) != 0) {
            compareStatistics(out, h1, h2, errorLimit);
        }
        return out;
    }

    /** compareStatistics(h1, h2, debug, ERRORLIMIT). */
    static void compareStatistics(Outcome out, LabHist h1, LabHist h2, double errorLimit) {
        // for profiles in 2D and 3D avoid problems with bins with zero error
        final boolean a1 = approximate(h1, true);
        final boolean a2 = approximate(h2, true);
        final double chi12 = chi2Test(h1, h2, "WW OF UF");
        final double chi21 = chi2Test(h2, h1, "WW OF UF");
        approximate(h1, a1);
        approximate(h2, a2);
        check(out, "Chi2Test(h1,h2)", chi12, 1, errorLimit);
        check(out, "Chi2Test(h2,h1)", chi21, 1, errorLimit);
        check(out, "Chi2Test symmetry", chi12, chi21, errorLimit);

        final String[] axes = {"X", "Y", "Z"};
        for (int idim = 1; idim <= h1.dimension; idim++) {
            check(out, "Mean(" + axes[idim - 1] + ")", h1.mean(idim), h2.mean(idim), errorLimit);
            check(out, "StdDev(" + axes[idim - 1] + ")", h1.stdDev(idim), h2.stdDev(idim), errorLimit);
        }
        final double[] s1 = h1.stats();
        final double[] s2 = h2.stats();
        check(out, "sum of weights", s1[0], s2[0], 100 * errorLimit);
        check(out, "GetSumOfWeights", h1.sumOfWeights(), h2.sumOfWeights(), 100 * errorLimit);
        check(out, "GetSumOfAllWeights", h1.sumOfAllWeights(true), h2.sumOfAllWeights(true), 100 * errorLimit);
        if (areEqualRel(s1[0], h1.effectiveEntries(), 1.E-12)) {
            check(out, "entries", h1.entries, h2.entries, 100 * errorLimit);
            if (!h1.isProfile()) {
                check(out, "entries vs sum of all weights", h1.entries, h2.sumOfAllWeights(true), 100 * errorLimit);
            }
        }
        check(out, "effective entries", h1.effectiveEntries(), h2.effectiveEntries(), 100 * errorLimit);
    }

    private static boolean approximate(LabHist h, boolean on) {
        if (h instanceof LabProfile p && p.dimension > 1) {
            final boolean was = p.approximate;
            p.approximate = on;
            return was;
        }
        return false;
    }

    /** TMath::Nint: the nearest integer, a half going to the even neighbour. */
    public static int nint(double x) {
        int i;
        if (x >= 0) {
            i = (int) (x + 0.5);
            if ((i & 1) != 0 && x + 0.5 == (double) i) {
                i--;
            }
        } else {
            i = (int) (x - 0.5);
            if ((i & 1) != 0 && x - 0.5 == (double) i) {
                i++;
            }
        }
        return i;
    }

    /** TMath::AreEqualRel. */
    static boolean areEqualRel(double af, double bf, double relPrec) {
        return Math.abs(af - bf) <= 0.5 * relPrec * (Math.abs(af) + Math.abs(bf))
            || Math.abs(af - bf) < Double.MIN_NORMAL;
    }

    // ---- Chi2Test --------------------------------------------------------------------

    /** The p-value, chi2 and number of degrees of freedom of a Chi2Test. */
    public record Chi2(double prob, double chi2, int ndf) {
    }

    /** TH1::Chi2Test: the probability (options UU, UW or WW, OF, UF as in ROOT). */
    public static double chi2Test(LabHist h1, LabHist h2, String option) {
        return chi2TestX(h1, h2, option).prob();
    }

    /** TH1::Chi2TestX, the unweighted/unweighted, unweighted/weighted and weighted/weighted cases. */
    public static Chi2 chi2TestX(LabHist h1, LabHist h2, String option) {
        final String opt = option.toUpperCase(Locale.ROOT);
        if (h1.dimension != h2.dimension) {
            return new Chi2(0, 0, 0);
        }
        int iStart = 1;
        int jStart = 1;
        int kStart = 1;
        int iEnd = h1.xaxis.nbins;
        int jEnd = h1.yaxis.nbins;
        int kEnd = h1.zaxis.nbins;
        if (opt.contains("OF")) {
            if (h1.dimension == 3) {
                kEnd++;
            }
            if (h1.dimension >= 2) {
                jEnd++;
            }
            iEnd++;
        }
        if (opt.contains("UF")) {
            if (h1.dimension == 3) {
                kStart = 0;
            }
            if (h1.dimension >= 2) {
                jStart = 0;
            }
            iStart = 0;
        }
        if (h1.dimension < 3) {
            kStart = kEnd = 0;
        }
        if (h1.dimension < 2) {
            jStart = jEnd = 0;
        }
        int ndf = (iEnd - iStart + 1) * (jEnd - jStart + 1) * (kEnd - kStart + 1) - 1;

        boolean uu = opt.contains("UU");
        boolean uw = opt.contains("UW");
        boolean ww = opt.contains("WW");
        if (!uu && !uw && !ww) {
            final double[] s1 = h1.stats();
            final double eff1 = s1[1] != 0 ? s1[0] * s1[0] / s1[1] : 0.0;
            final double[] s2 = h2.stats();
            final double eff2 = s2[1] != 0 ? s2[0] * s2[0] / s2[1] : 0.0;
            if (Math.abs(s1[0] - eff1) < 1) {
                if (Math.abs(s2[0] - eff2) < 1) {
                    uu = true;
                } else {
                    uw = true;
                }
            } else {
                ww = true;
            }
        }

        double sum1 = 0;
        double sum2 = 0;
        double sumw1 = 0;
        double sumw2 = 0;
        for (int i = iStart; i <= iEnd; ++i) {
            for (int j = jStart; j <= jEnd; ++j) {
                for (int k = kStart; k <= kEnd; ++k) {
                    final int bin = h1.bin(i, j, k);
                    sum1 += h1.content(bin);
                    sum2 += h2.content(bin);
                    if (ww) {
                        sumw1 += h1.errorSq(bin);
                    }
                    if (uw || ww) {
                        sumw2 += h2.errorSq(bin);
                    }
                }
            }
        }
        if (sum1 == 0.0 || sum2 == 0.0) {
            return new Chi2(0, 0, ndf);
        }
        if (ww && sumw1 <= 0.0 && sumw2 <= 0.0) {
            return new Chi2(0, 0, ndf);
        }

        double chi2 = 0;
        if (uu) {
            final double sum = sum1 + sum2;
            for (int i = iStart; i <= iEnd; ++i) {
                for (int j = jStart; j <= jEnd; ++j) {
                    for (int k = kStart; k <= kEnd; ++k) {
                        final int bin = h1.bin(i, j, k);
                        final double cnt1 = h1.content(bin);
                        final double cnt2 = h2.content(bin);
                        if ((int) cnt1 == 0 && (int) cnt2 == 0) {
                            --ndf;
                        } else {
                            final double cntsum = cnt1 + cnt2;
                            final double delta = sum2 * cnt1 - sum1 * cnt2;
                            chi2 += delta * delta / cntsum;
                        }
                    }
                }
            }
            chi2 /= sum1 * sum2;
            return new Chi2(prob(chi2, ndf), chi2, ndf);
        }

        if (uw) {
            for (int i = iStart; i <= iEnd; ++i) {
                for (int j = jStart; j <= jEnd; ++j) {
                    for (int k = kStart; k <= kEnd; ++k) {
                        final int bin = h1.bin(i, j, k);
                        double cnt1 = h1.content(bin);
                        final double cnt2 = h2.content(bin);
                        double e2sq = h2.errorSq(bin);
                        if (cnt1 * cnt1 == 0 && cnt2 * cnt2 == 0) {
                            --ndf;
                            continue;
                        }
                        if (cnt2 * cnt2 == 0 && e2sq == 0) {
                            if (sumw2 > 0) {
                                e2sq = sumw2 / sum2;
                            } else {
                                return new Chi2(0, 0, ndf);
                            }
                        }
                        double var1 = sum2 * cnt2 - sum1 * e2sq;
                        double var2 = var1 * var1 + 4. * sum2 * sum2 * cnt1 * e2sq;
                        while (var1 * var1 + cnt1 == 0 || var1 + var2 == 0) {
                            sum1++;
                            cnt1++;
                            var1 = sum2 * cnt2 - sum1 * e2sq;
                            var2 = var1 * var1 + 4. * sum2 * sum2 * cnt1 * e2sq;
                        }
                        var2 = Math.sqrt(var2);
                        while (var1 + var2 == 0) {
                            sum1++;
                            cnt1++;
                            var1 = sum2 * cnt2 - sum1 * e2sq;
                            var2 = var1 * var1 + 4. * sum2 * sum2 * cnt1 * e2sq;
                            while (var1 * var1 + cnt1 == 0 || var1 + var2 == 0) {
                                sum1++;
                                cnt1++;
                                var1 = sum2 * cnt2 - sum1 * e2sq;
                                var2 = var1 * var1 + 4. * sum2 * sum2 * cnt1 * e2sq;
                            }
                            var2 = Math.sqrt(var2);
                        }
                        final double probb = (var1 + var2) / (2. * sum2 * sum2);
                        final double nexp1 = probb * sum1;
                        final double nexp2 = probb * sum2;
                        final double delta1 = cnt1 - nexp1;
                        final double delta2 = cnt2 - nexp2;
                        chi2 += delta1 * delta1 / nexp1;
                        if (e2sq > 0) {
                            chi2 += delta2 * delta2 / e2sq;
                        }
                    }
                }
            }
            return new Chi2(prob(chi2, ndf), chi2, ndf);
        }

        // weighted against weighted
        for (int i = iStart; i <= iEnd; ++i) {
            for (int j = jStart; j <= jEnd; ++j) {
                for (int k = kStart; k <= kEnd; ++k) {
                    final int bin = h1.bin(i, j, k);
                    final double cnt1 = h1.content(bin);
                    final double cnt2 = h2.content(bin);
                    final double e1sq = h1.errorSq(bin);
                    final double e2sq = h2.errorSq(bin);
                    if (cnt1 * cnt1 == 0 && cnt2 * cnt2 == 0) {
                        --ndf;
                        continue;
                    }
                    if (e1sq == 0 && e2sq == 0) {
                        return new Chi2(0, 0, ndf);
                    }
                    final double sigma = sum1 * sum1 * e2sq + sum2 * sum2 * e1sq;
                    final double delta = sum2 * cnt1 - sum1 * cnt2;
                    chi2 += delta * delta / sigma;
                }
            }
        }
        return new Chi2(prob(chi2, ndf), chi2, ndf);
    }

    // ---- TMath::Prob ------------------------------------------------------------------

    /** TMath::Prob: the probability that a chi2 at least this large arises by chance with ndf degrees. */
    public static double prob(double chi2, int ndf) {
        if (ndf <= 0) {
            return 0;
        }
        if (chi2 <= 0) {
            return chi2 < 0 ? 0 : 1;
        }
        return igamc(0.5 * ndf, 0.5 * chi2);
    }

    private static final double MACHEP = 1.11022302462515654042363166809e-16;
    private static final double MAXLOG = 709.782712893383973096206318587;
    private static final double BIG = 4.503599627370496e15;
    private static final double BIGINV = 2.22044604925031308085e-16;

    /** Cephes igamc: the complemented regularized incomplete gamma function Q(a, x). */
    public static double igamc(double a, double x) {
        if (x < 0 || a <= 0) {
            return 1.0;
        }
        if (x < 1.0 || x < a) {
            return 1.0 - igam(a, x);
        }
        double ax = a * Math.log(x) - x - lnGamma(a);
        if (ax < -MAXLOG) {
            return 0.0;
        }
        ax = Math.exp(ax);
        double y = 1.0 - a;
        double z = x + y + 1.0;
        double c = 0.0;
        double pkm2 = 1.0;
        double qkm2 = x;
        double pkm1 = x + 1.0;
        double qkm1 = z * x;
        double ans = pkm1 / qkm1;
        double t;
        do {
            c += 1.0;
            y += 1.0;
            z += 2.0;
            final double yc = y * c;
            final double pk = pkm1 * z - pkm2 * yc;
            final double qk = qkm1 * z - qkm2 * yc;
            if (qk != 0) {
                final double r = pk / qk;
                t = Math.abs((ans - r) / r);
                ans = r;
            } else {
                t = 1.0;
            }
            pkm2 = pkm1;
            pkm1 = pk;
            qkm2 = qkm1;
            qkm1 = qk;
            if (Math.abs(pk) > BIG) {
                pkm2 *= BIGINV;
                pkm1 *= BIGINV;
                qkm2 *= BIGINV;
                qkm1 *= BIGINV;
            }
        } while (t > MACHEP);
        return ans * ax;
    }

    /** Cephes igam: the regularized incomplete gamma function P(a, x). */
    public static double igam(double a, double x) {
        if (x <= 0 || a <= 0) {
            return 0.0;
        }
        if (x > 1.0 && x > a) {
            return 1.0 - igamc(a, x);
        }
        double ax = a * Math.log(x) - x - lnGamma(a);
        if (ax < -MAXLOG) {
            return 0.0;
        }
        ax = Math.exp(ax);
        double r = a;
        double c = 1.0;
        double ans = 1.0;
        do {
            r += 1.0;
            c *= x / r;
            ans += c;
        } while (c / ans > MACHEP);
        return ans * ax / a;
    }

    /** ln Γ(x), Lanczos (g = 7, n = 9). */
    static double lnGamma(double x) {
        final double[] c = {
            0.99999999999980993, 676.5203681218851, -1259.1392167224028,
            771.32342877765313, -176.61502916214059, 12.507343278686905,
            -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7};
        if (x < 0.5) {
            return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - lnGamma(1 - x);
        }
        final double xx = x - 1;
        double a = c[0];
        final double t = xx + 7.5;
        for (int i = 1; i < 9; i++) {
            a += c[i] / (xx + i);
        }
        return 0.5 * Math.log(2 * Math.PI) + (xx + 0.5) * Math.log(t) - t + Math.log(a);
    }

    // ---- Kolmogorov -----------------------------------------------------------------------

    /**
     * TH1::KolmogorovTest for one-dimensional histograms (no options): the
     * probability that the two come from the same distribution, from the
     * largest distance between their cumulative contents.
     */
    public static double kolmogorov(LabHist h1, LabHist h2) {
        if (h1.dimension != 1 || h2.dimension != 1 || h1.xaxis.nbins != h2.xaxis.nbins) {
            return 0;
        }
        final int n = h1.xaxis.nbins;
        double sum1 = 0;
        double sum2 = 0;
        double w1 = 0;
        double w2 = 0;
        for (int bin = 1; bin <= n; bin++) {
            sum1 += h1.content(bin);
            sum2 += h2.content(bin);
            w1 += h1.errorSq(bin);
            w2 += h2.errorSq(bin);
        }
        if (sum1 == 0 || sum2 == 0) {
            return 0;
        }
        final double esum1 = w1 > 0 ? sum1 * sum1 / w1 : sum1;
        final double esum2 = w2 > 0 ? sum2 * sum2 / w2 : sum2;
        double rsum1 = 0;
        double rsum2 = 0;
        double dfmax = 0;
        for (int bin = 1; bin <= n; bin++) {
            rsum1 += h1.content(bin) / sum1;
            rsum2 += h2.content(bin) / sum2;
            dfmax = Math.max(dfmax, Math.abs(rsum1 - rsum2));
        }
        final double z = dfmax * Math.sqrt(esum1 * esum2 / (esum1 + esum2));
        return kolmogorovProb(z);
    }

    /** TMath::KolmogorovProb. */
    public static double kolmogorovProb(double z) {
        final double[] fj = {-2, -8, -18, -32};
        final double w = 2.50662827;
        final double c1 = -1.2337005501361697;
        final double c2 = -11.103304951225528;
        final double c3 = -30.842513753404244;
        final double u = Math.abs(z);
        double p;
        if (u < 0.2) {
            p = 1;
        } else if (u < 0.755) {
            final double v = 1. / (u * u);
            p = 1 - w * (Math.exp(c1 * v) + Math.exp(c2 * v) + Math.exp(c3 * v)) / u;
        } else if (u < 6.8116) {
            final double[] r = new double[4];
            final int maxj = Math.max(1, (int) Math.round(3. / u));
            for (int j = 0; j < maxj; j++) {
                r[j] = Math.exp(fj[j] * u * u);
            }
            p = 2 * (r[0] - r[1] + r[2] - r[3]);
        } else {
            p = 0;
        }
        return p;
    }
}
