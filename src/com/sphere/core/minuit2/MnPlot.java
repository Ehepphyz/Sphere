package com.sphere.core.minuit2;

import com.sphere.core.hepmc3.cxx.CFormat;

import java.util.List;
import java.util.function.Consumer;

/**
 * A text plot of (x, y) points, as Minuit has drawn scans and contours since
 * its Fortran days (mnplot and mnbins, through f2c): a page of characters,
 * the y scale on the left, the x values below.
 */
public final class MnPlot {

    private static volatile Consumer<String> output = s -> {
        System.out.print(s);
        System.out.flush();
    };

    private final int pageWidth;
    private final int pageLength;

    public MnPlot() {
        this(80, 30);
    }

    public MnPlot(int width, int length) {
        pageWidth = Math.min(width, 120);
        pageLength = Math.min(length, 56);
    }

    /** Where the plots go (the C++ printf's them on standard output). */
    public static void setOutput(Consumer<String> out) {
        output = out;
    }

    public int width() {
        return pageWidth;
    }

    public int length() {
        return pageLength;
    }

    /** Plots the points with '*'. */
    public void plot(List<MnPrint.Point> points) {
        output.accept(render(points));
    }

    /** Plots the points with '*' and the minimum (xmin, ymin) with 'X'. */
    public void plot(double xmin, double ymin, List<MnPrint.Point> points) {
        output.accept(render(xmin, ymin, points));
    }

    public String render(List<MnPrint.Point> points) {
        final int n = points.size();
        final double[] x = new double[n];
        final double[] y = new double[n];
        final char[] chpt = new char[n];
        for (int i = 0; i < n; i++) {
            x[i] = points.get(i).x();
            y[i] = points.get(i).y();
            chpt[i] = '*';
        }
        final StringBuilder out = new StringBuilder();
        mnplot(x, y, chpt, n, width(), length(), out);
        return out.toString();
    }

    public String render(double xmin, double ymin, List<MnPrint.Point> points) {
        final int n = points.size() + 2;
        final double[] x = new double[n];
        final double[] y = new double[n];
        final char[] chpt = new char[n];
        x[0] = xmin;
        x[1] = xmin;
        y[0] = ymin;
        y[1] = ymin;
        chpt[0] = ' ';
        chpt[1] = 'X';
        for (int i = 0; i < points.size(); i++) {
            x[i + 2] = points.get(i).x();
            y[i + 2] = points.get(i).y();
            chpt[i + 2] = '*';
        }
        final StringBuilder out = new StringBuilder();
        mnplot(x, y, chpt, n, width(), length(), out);
        return out.toString();
    }

    /** mnbins: a bin width of 2, 2.5, 5 or 10 times a power of ten, and the bounds rounded to it. */
    record Bins(double bl, double bh, int nb, double bwid) {
    }

    static Bins mnbins(double a1, double a2, int naa, double bwidIn) {
        double bwid = bwidIn;
        double awid, ah, al, sigfig, sigrnd, alb;
        int kwid, lwid, na = 0, log;
        al = a1 < a2 ? a1 : a2;
        ah = a1 > a2 ? a1 : a2;
        if (al == ah) ah = al + 1;
        boolean computeWidth = naa != -1 || bwid <= 0;
        if (computeWidth) {
            na = naa - 1;
            if (na < 1) na = 1;
        }
        while (true) {
            if (computeWidth) {
                awid = (ah - al) / (double) na;
                log = (int) Cxx.log10(awid);
                if (awid <= 1) --log;
                sigfig = awid * Cxx.pow(10.0, -log);
                if (sigfig <= 2) {
                    sigrnd = 2;
                } else if (sigfig <= 2.5) {
                    sigrnd = 2.5;
                } else if (sigfig <= 5) {
                    sigrnd = 5;
                } else {
                    sigrnd = 1;
                    ++log;
                }
                bwid = sigrnd * Cxx.pow(10.0, log);
            }
            alb = al / bwid;
            lwid = (int) alb;
            if (alb < 0) --lwid;
            final double bl = bwid * (double) lwid;
            alb = ah / bwid + 1;
            kwid = (int) alb;
            if (alb < 0) --kwid;
            final double bh = bwid * (double) kwid;
            int nb = kwid - lwid;
            if (naa > 5) {
                if (nb << 1 != naa) return new Bins(bl, bh, nb, bwid);
                ++na;
                computeWidth = true;
                continue;
            }
            if (naa == -1) return new Bins(bl, bh, nb, bwid);
            if (naa > 1 || nb == 1) return new Bins(bl, bh, nb, bwid);
            bwid *= 2;
            nb = 1;
            return new Bins(bl, bh, nb, bwid);
        }
    }

    /** mnplot: sorts the points by y, scales them on the page and prints it. */
    static void mnplot(double[] xpt, double[] ypt, char[] chpt, int nxypt, int npagwd, int npagln, StringBuilder out) {
        double xmin, ymin, xmax, ymax, yprt;
        double bwidx, bwidy, xbest, ybest, ax, ay, bx, by;
        final double[] xvalus = new double[12];
        double any, dxx, dyy;
        int maxnx, maxny, iquit, ni, linodd, nxbest, nybest, km1, isp1, nx, ny, ks, ix;
        final char[] cline = new char[120];
        boolean overpr;
        char chbest;
        maxnx = npagwd - 20 < 100 ? npagwd - 20 : 100;
        if (maxnx < 10) maxnx = 10;
        maxny = npagln;
        if (maxny < 10) maxny = 10;
        if (nxypt <= 1) return;
        xbest = xpt[0];
        ybest = ypt[0];
        chbest = chpt[0];
        km1 = nxypt - 1;
        for (int i = 1; i <= km1; ++i) {
            iquit = 0;
            ni = nxypt - i;
            for (int j = 1; j <= ni; ++j) {
                if (ypt[j - 1] > ypt[j]) continue;
                final double saveX = xpt[j - 1];
                xpt[j - 1] = xpt[j];
                xpt[j] = saveX;
                final double saveY = ypt[j - 1];
                ypt[j - 1] = ypt[j];
                ypt[j] = saveY;
                final char chsav = chpt[j - 1];
                chpt[j - 1] = chpt[j];
                chpt[j] = chsav;
                iquit = 1;
            }
            if (iquit == 0) break;
        }
        xmax = xpt[0];
        xmin = xmax;
        for (int i = 1; i <= nxypt; ++i) {
            if (xpt[i - 1] > xmax) xmax = xpt[i - 1];
            if (xpt[i - 1] < xmin) xmin = xpt[i - 1];
        }
        dxx = (xmax - xmin) * .001;
        xmax += dxx;
        xmin -= dxx;
        final Bins bxs = mnbins(xmin, xmax, maxnx, 0.);
        xmin = bxs.bl();
        xmax = bxs.bh();
        nx = bxs.nb();
        bwidx = bxs.bwid();
        ymax = ypt[0];
        ymin = ypt[nxypt - 1];
        if (ymax == ymin) ymax = ymin + 1;
        dyy = (ymax - ymin) * .001;
        ymax += dyy;
        ymin -= dyy;
        final Bins bys = mnbins(ymin, ymax, maxny, 0.);
        ymin = bys.bl();
        ymax = bys.bh();
        ny = bys.nb();
        bwidy = bys.bwid();
        any = (double) ny;
        if (chbest != ' ') {
            xbest = (xmax + xmin) * .5;
            ybest = (ymax + ymin) * .5;
        }
        ax = 1 / bwidx;
        ay = 1 / bwidy;
        bx = -ax * xmin + 2;
        by = -ay * ymin - 2;
        for (int i = 1; i <= nxypt; ++i) {
            xpt[i - 1] = ax * xpt[i - 1] + bx;
            ypt[i - 1] = any - ay * ypt[i - 1] - by;
        }
        nxbest = (int) (ax * xbest + bx);
        nybest = (int) (any - ay * ybest - by);
        ny += 2;
        nx += 2;
        isp1 = 1;
        linodd = 1;
        overpr = false;
        for (int i = 1; i <= ny; ++i) {
            for (int ibk = 1; ibk <= nx; ++ibk) cline[ibk - 1] = ' ';
            set(cline, nx, '\0');
            set(cline, nx + 1, '\0');
            cline[0] = '.';
            if (nx > 0) cline[nx - 1] = '.';
            set(cline, nxbest - 1, '.');
            if (i == 1 || i == nybest || i == ny) {
                for (int j = 1; j <= nx; ++j) cline[j - 1] = '.';
            }
            yprt = ymax - (double) (i - 1) * bwidy;
            if (isp1 <= nxypt) {
                int k;
                boolean stopped = false;
                for (k = isp1; k <= nxypt; ++k) {
                    ks = (int) ypt[k - 1];
                    if (ks > i) {
                        stopped = true;
                        break;
                    }
                    ix = (int) xpt[k - 1];
                    if (ix < 1 || ix > cline.length) continue;
                    if (cline[ix - 1] == '.' || cline[ix - 1] == ' ') {
                        cline[ix - 1] = chpt[k - 1];
                        continue;
                    }
                    if (cline[ix - 1] == chpt[k - 1]) continue;
                    overpr = true;
                    cline[ix - 1] = '&';
                }
                isp1 = stopped ? k : nxypt + 1;
            }
            if (linodd == 1 || i == ny) {
                out.append(CFormat.sprintf(" %14.7g ..", yprt)).append(str(cline));
                linodd = 0;
            } else {
                linodd = 1;
                out.append("                  ").append(str(cline));
            }
            out.append("\n");
        }
        for (int ibk = 1; ibk <= nx; ++ibk) {
            cline[ibk - 1] = ' ';
            if (ibk % 10 == 1) cline[ibk - 1] = '/';
        }
        out.append("                  ").append(str(cline));
        out.append("\n");
        for (int ibk = 1; ibk <= 12; ++ibk) xvalus[ibk - 1] = xmin + (double) (ibk - 1) * 10 * bwidx;
        out.append("           ");
        final int iTen = (nx + 9) / 10;
        for (int ibk = 1; ibk <= iTen && ibk <= 12; ++ibk) out.append(CFormat.sprintf(" %9.4g", xvalus[ibk - 1]));
        out.append("\n");
        if (overpr) {
            out.append(CFormat.sprintf("                         ONE COLUMN=%13.7g", bwidx)).append("   Overprint character is &");
        } else {
            out.append(CFormat.sprintf("                         ONE COLUMN=%13.7g", bwidx)).append(" ");
        }
        out.append("\n");
    }

    private static void set(char[] a, int i, char c) {
        if (i >= 0 && i < a.length) a[i] = c;
    }

    /** The C string in the line: up to its first NUL. */
    private static String str(char[] a) {
        int n = 0;
        while (n < a.length && a[n] != '\0') n++;
        return new String(a, 0, n);
    }
}
