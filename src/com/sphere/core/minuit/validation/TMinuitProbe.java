package com.sphere.core.minuit.validation;

import com.sphere.core.hepmc3.cxx.NativeMath;
import com.sphere.core.minuit.TMinuit;

import java.io.PrintStream;

/**
 * The twin of tmprobe.cxx: drives TMinuit through its commands and its API on
 * a Rosenbrock function, a Gaussian fit with limits, a quartic with its
 * gradient and a double well, lets it print what it prints and writes every
 * result as the 64 bits of the double, so that the output can be compared
 * line for line with ROOT's TMinuit compiled from root-master.
 */
public final class TMinuitProbe {

    private final PrintStream out;
    private final PrintStream err;

    private TMinuitProbe(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    private void H(String label, double d) {
        out.printf("%s=%016x\n", label, Double.doubleToRawLongBits(d));
    }

    private void dump(TMinuit m, String tag) {
        final double[] fmin = {0}, fedm = {0}, errdef = {0};
        final int[] npari = {0}, nparx = {0}, istat = {0};
        m.mnstat(fmin, fedm, errdef, npari, nparx, istat);
        out.printf("== %s: npari=%d nparx=%d istat=%d status=%d\n", tag, npari[0], nparx[0], istat[0], m.GetStatus());
        H("  fmin", fmin[0]);
        H("  fedm", fedm[0]);
        H("  errdef", errdef[0]);
        for (int i = 0; i < nparx[0]; i++) {
            final double[] v = {0}, e = {0};
            m.GetParameter(i, v, e);
            final double[] ep = {0}, en = {0}, eparab = {0}, gcc = {0};
            m.mnerrs(i, ep, en, eparab, gcc);
            H("  p" + i, v[0]);
            H("  e" + i, e[0]);
            H("  eplus" + i, ep[0]);
            H("  eminus" + i, en[0]);
            H("  eparab" + i, eparab[0]);
            H("  gcc" + i, gcc[0]);
        }
        if (npari[0] > 0) {
            final double[] emat = new double[400];
            m.mnemat(emat, npari[0]);
            for (int i = 0; i < npari[0] * npari[0]; i++) H("  emat" + i, emat[i]);
        }
        out.flush();
    }

    private static void rosen(int[] npar, double[] gin, double[] f, double[] x, int flag) {
        final double a = x[1] - x[0] * x[0];
        final double b = 1. - x[0];
        f[0] = 100. * a * a + b * b;
    }

    private static final double[] gx = new double[60], gy = new double[60], gv = new double[60];

    private static void makeData() {
        for (int i = 0; i < 60; i++) {
            final double x = -4. + 0.15 * i;
            final double y = 12. * NativeMath.exp(-0.5 * (x - 0.4) * (x - 0.4) / (1.1 * 1.1)) + 0.3 * NativeMath.sin(3.7 * i) + 0.5;
            gx[i] = x;
            gy[i] = y;
            gv[i] = 0.04 + 0.01 * y;
        }
    }

    private static void gauss(int[] npar, double[] gin, double[] f, double[] p, int flag) {
        double c = 0.;
        for (int i = 0; i < 60; i++) {
            final double t = (gx[i] - p[0]) / p[1];
            final double v = p[2] * NativeMath.exp(-0.5 * t * t) + p[3];
            final double r = v - gy[i];
            c += r * r / gv[i];
        }
        f[0] = c;
    }

    private static int gradCalls = 0;

    private static void quartic(int[] npar, double[] gin, double[] f, double[] p, int iflag) {
        final double x = p[0], y = p[1], z = p[2];
        final double t = x - 1.;
        f[0] = t * t * t * t + 2. * (y + 0.5) * (y + 0.5) + NativeMath.cos(z) + 0.1 * x * y + z * z * 0.05;
        if (iflag == 2) {
            gradCalls++;
            gin[0] = 4. * t * t * t + 0.1 * y;
            gin[1] = 4. * (y + 0.5) + 0.1 * x;
            gin[2] = -NativeMath.sin(z) + 0.1 * z;
        }
    }

    private static void doubleWell(int[] npar, double[] gin, double[] f, double[] p, int flag) {
        final double x = p[0], y = p[1];
        f[0] = (x * x - 1.) * (x * x - 1.) + (y - 0.3 * x) * (y - 0.3 * x) + 0.05 * x;
    }

    private void cmd(TMinuit m, String c) {
        out.printf(">> %s\n", c);
        out.flush();
        final int st = m.Command(c);
        out.printf("<< status %d\n", st);
        out.flush();
    }

    private TMinuit make(int n, TMinuit.FCN fcn) {
        final TMinuit m = new TMinuit(n);
        m.setOutput(out);
        m.setErrorOutput(err);
        m.setInput(java.io.InputStream.nullInputStream());
        m.SetFCN(fcn);
        return m;
    }

    /** The whole probe: what TMinuit and the probe print to out (ROOT's standard output), the warnings to err. */
    public static void run(PrintStream out, PrintStream err) {
        TMinuit.resetRandomSeed();
        gradCalls = 0;
        new TMinuitProbe(out, err).body();
        out.flush();
    }

    private void body() {
        makeData();
        {
            out.printf("######## Rosenbrock, print level 1\n");
            final TMinuit m = make(2, TMinuitProbe::rosen);
            final int[] ierflg = {0};
            final double[] arglist = new double[10];
            m.mnparm(0, "x", -1.2, 0.1, 0, 0, ierflg);
            m.mnparm(1, "y", 1.0, 0.1, 0, 0, ierflg);
            cmd(m, "SET PRINT 1");
            cmd(m, "MIGRAD");
            dump(m, "rosen migrad");
            cmd(m, "HESSE");
            cmd(m, "MINOS");
            dump(m, "rosen minos");
            cmd(m, "SHOW COV");
            cmd(m, "SHOW COR");
            cmd(m, "SHOW EIG");
            cmd(m, "SHOW FCN");
            cmd(m, "SHOW EDM");
            cmd(m, "SHOW PAR");
            cmd(m, "SHOW STR");
            cmd(m, "SHOW ERR");
            cmd(m, "SHOW EPS");
            cmd(m, "SHOW LIM");
            arglist[0] = 500;
            m.mnexcm("SIMPLEX", arglist, 1, ierflg);
            out.printf("simplex ierflg %d\n", ierflg[0]);
            dump(m, "rosen simplex");
        }
        {
            out.printf("######## Rosenbrock, strategies and errors\n");
            for (int s = 0; s <= 2; s++) {
                final TMinuit m = make(2, TMinuitProbe::rosen);
                m.SetPrintLevel(-1);
                m.DefineParameter(0, "x", -1.2, 0.1, 0, 0);
                m.DefineParameter(1, "y", 1.0, 0.1, 0, 0);
                cmd(m, "SET STR " + s);
                final int st = m.Migrad();
                out.printf("migrad returns %d\n", st);
                dump(m, "rosen strategy " + s);
            }
        }
        {
            out.printf("######## Gaussian fit with limits, print level 0\n");
            final TMinuit m = make(4, TMinuitProbe::gauss);
            final int[] ierflg = {0};
            m.mnparm(0, "mean", 0., 0.2, -1., 2., ierflg);
            m.mnparm(1, "sigma", 1.5, 0.1, 0.2, 5., ierflg);
            m.mnparm(2, "area", 10., 1., 0., 50., ierflg);
            m.mnparm(3, "bkg", 0.3, 0.1, 0, 0, ierflg);
            cmd(m, "SET PRINT 0");
            cmd(m, "MIGRAD");
            dump(m, "gauss migrad");
            cmd(m, "HESSE");
            cmd(m, "MINOS 1000 1 2");
            dump(m, "gauss minos");
            cmd(m, "SET ERR 4");
            cmd(m, "MINOS");
            dump(m, "gauss minos up4");
            cmd(m, "SET ERR 1");
            cmd(m, "FIX 4");
            cmd(m, "MIGRAD");
            dump(m, "gauss fixed bkg");
            cmd(m, "RELEASE 4");
            cmd(m, "SET LIM 1 -0.5 0.8");
            cmd(m, "MIGRAD");
            dump(m, "gauss new limits");
            cmd(m, "SET PAR 1 0.45");
            cmd(m, "SET LIM 1");
            cmd(m, "IMPROVE");
            dump(m, "gauss improve");
            cmd(m, "SCAN 2 11 0.9 1.4");
            cmd(m, "SEEK 30 2");
            dump(m, "gauss seek");
            cmd(m, "MNCONTOUR 1 2 12");
            final double[] xc = new double[20], yc = new double[20];
            final int[] ierrf = {0};
            m.mncont(0, 1, 8, xc, yc, ierrf);
            out.printf("mncont ierrf %d\n", ierrf[0]);
            for (int i = 0; i < 8; i++) {
                H("  xc", xc[i]);
                H("  yc", yc[i]);
            }
            cmd(m, "SHOW COR");
            cmd(m, "SHOW COV");
            cmd(m, "CALL FCN 3");
            cmd(m, "SET TITLE");
            cmd(m, "SHOW TITLE");
            cmd(m, "SHOW VERSION");
            cmd(m, "SHOW RAN");
            cmd(m, "SET EPS 1e-12");
            cmd(m, "MIGRAD");
            dump(m, "gauss eps");
        }
        {
            out.printf("######## Quartic with its gradient\n");
            final TMinuit m = make(3, TMinuitProbe::quartic);
            final int[] ierflg = {0};
            m.mnparm(0, "x", 3., 0.5, 0, 0, ierflg);
            m.mnparm(1, "y", 1., 0.5, 0, 0, ierflg);
            m.mnparm(2, "z", 1., 0.3, 0, 0, ierflg);
            cmd(m, "SET GRAD");
            cmd(m, "MIGRAD");
            out.printf("gradient calls %d\n", gradCalls);
            dump(m, "quartic grad");
            cmd(m, "SET NOGRAD");
            cmd(m, "SET STR 2");
            cmd(m, "MIGRAD");
            dump(m, "quartic nograd s2");
            cmd(m, "HESSE");
            dump(m, "quartic hesse");
        }
        {
            out.printf("######## Double well: SEEK, MINIMIZE, IMPROVE\n");
            final TMinuit m = make(2, TMinuitProbe::doubleWell);
            final int[] ierflg = {0};
            m.mnparm(0, "x", 0.02, 0.1, 0, 0, ierflg);
            m.mnparm(1, "y", 0.0, 0.1, 0, 0, ierflg);
            cmd(m, "SET PRINT 1");
            cmd(m, "MINIMIZE");
            dump(m, "dw minimize");
            cmd(m, "IMPROVE 30");
            dump(m, "dw improve");
            cmd(m, "SEEK 50 3");
            dump(m, "dw seek");
            cmd(m, "MINOS 500 1");
            dump(m, "dw minos");
        }
        {
            out.printf("######## Help and errors\n");
            final TMinuit m = make(2, TMinuitProbe::rosen);
            final int[] ierflg = {0};
            m.mnparm(0, "x", -1.2, 0.1, 0, 0, ierflg);
            m.mnparm(1, "y", 1.0, 0.1, 0, 0, ierflg);
            cmd(m, "HELP");
            cmd(m, "HELP MIGRAD");
            cmd(m, "HELP SET");
            cmd(m, "HELP SHOW");
            cmd(m, "BOGUS COMMAND");
            cmd(m, "SET BOGUS");
            cmd(m, "MIGRAD 10 1");
            dump(m, "rosen limited calls");
            cmd(m, "SET PRINT 3");
            cmd(m, "MIGRAD");
            dump(m, "rosen print 3");
        }
    }

    public static void main(String[] args) {
        final PrintStream ps = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false,
            java.nio.charset.StandardCharsets.UTF_8);
        run(ps, new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, java.nio.charset.StandardCharsets.UTF_8));
        ps.flush();
    }
}
