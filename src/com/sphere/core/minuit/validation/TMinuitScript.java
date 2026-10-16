package com.sphere.core.minuit.validation;

import com.sphere.core.hepmc3.cxx.NativeMath;
import com.sphere.core.minuit.TMinuit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The twin of tmscript.cxx: runs a scenario file line by line against TMinuit
 * (every command, SET and SHOW option, the printer plots, the parameter
 * definitions and errors, the warnings) and prints what TMinuit prints plus
 * each result as the 64 bits of the double. ROOT's TMinuit compiled from
 * root-master running the same file prints the same text.
 *
 * <p>Operations: new (maxpar|default) fcn, graphics 0|1, parm and define
 * (k name value step low high), cmd (a MINUIT command), excm, dump, pout,
 * emat, nums, fix, release, errdef, printlevel, migrad, contour, mncont,
 * help, unpt, pars, counts.
 */
public final class TMinuitScript {

    private final PrintStream out;
    private final PrintStream err;
    private TMinuit m;
    private int nGrad, nFlag1, nFlag3;

    private TMinuitScript(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    private void H(String label, double d) {
        out.printf("%s=%016x\n", label, Double.doubleToRawLongBits(d));
    }

    private void dump(String tag) {
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
        out.flush();
    }

    /* ------------------------------------------------------------------ */
    /* The functions                                                       */
    /* ------------------------------------------------------------------ */

    private void count(int flag) {
        if (flag == 1) nFlag1++;
        if (flag == 3) nFlag3++;
    }

    private static final double[] gx = new double[60], gy = new double[60], gv = new double[60];

    static {
        for (int i = 0; i < 60; i++) {
            final double x = -4. + 0.15 * i;
            final double y = 12. * NativeMath.exp(-0.5 * (x - 0.4) * (x - 0.4) / (1.1 * 1.1)) + 0.3 * NativeMath.sin(3.7 * i) + 0.5;
            gx[i] = x;
            gy[i] = y;
            gv[i] = 0.04 + 0.01 * y;
        }
    }

    private TMinuit.FCN byName(String n) {
        return switch (n) {
            case "rosen" -> (np, gin, f, x, flag) -> {
                count(flag);
                final double a = x[1] - x[0] * x[0];
                final double b = 1. - x[0];
                f[0] = 100. * a * a + b * b;
            };
            case "gauss" -> (np, gin, f, p, flag) -> {
                count(flag);
                double c = 0.;
                for (int i = 0; i < 60; i++) {
                    final double t = (gx[i] - p[0]) / p[1];
                    final double v = p[2] * NativeMath.exp(-0.5 * t * t) + p[3];
                    final double r = v - gy[i];
                    c += r * r / gv[i];
                }
                f[0] = c;
            };
            case "quartic" -> (np, gin, f, p, iflag) -> {
                count(iflag);
                final double x = p[0], y = p[1], z = p[2];
                final double t = x - 1.;
                f[0] = t * t * t * t + 2. * (y + 0.5) * (y + 0.5) + NativeMath.cos(z) + 0.1 * x * y + z * z * 0.05;
                if (iflag == 2) {
                    nGrad++;
                    gin[0] = 4. * t * t * t + 0.1 * y;
                    gin[1] = 4. * (y + 0.5) + 0.1 * x;
                    gin[2] = -NativeMath.sin(z) + 0.1 * z;
                }
            };
            case "badgrad" -> (np, gin, f, p, iflag) -> {
                count(iflag);
                final double x = p[0], y = p[1];
                f[0] = (x - 2.) * (x - 2.) + 3. * (y + 1.) * (y + 1.) + 0.5 * x * y;
                if (iflag == 2) {
                    gin[0] = 2. * (x - 2.) + 0.5 * y;
                    gin[1] = 6. * (y + 1.) + 0.5 * x + 1.;
                }
            };
            case "doubleWell" -> (np, gin, f, p, flag) -> {
                count(flag);
                final double x = p[0], y = p[1];
                f[0] = (x * x - 1.) * (x * x - 1.) + (y - 0.3 * x) * (y - 0.3 * x) + 0.05 * x;
            };
            case "bowl3" -> (np, gin, f, p, flag) -> {
                count(flag);
                final double x = p[0] - 1., y = p[1] + 0.5, z = p[2];
                f[0] = x * x + 2. * y * y + 3. * z * z + 1.8 * x * y + 0.5 * x * z + 0.1 * x * x * x * x + 0.05 * y * y * y;
            };
            case "saddle" -> (np, gin, f, p, flag) -> {
                count(flag);
                final double x = p[0], y = p[1];
                f[0] = x * x - y * y + 0.01 * y * y * y * y + 0.2 * x * y;
            };
            case "flat" -> (np, gin, f, p, flag) -> {
                count(flag);
                f[0] = (p[0] - 0.5) * (p[0] - 0.5) + 1e-30 * p[1];
            };
            case "nanfcn" -> (np, gin, f, p, flag) -> {
                count(flag);
                f[0] = Math.sqrt(p[0]) + (p[1] - 1.) * (p[1] - 1.) - p[0] * 0.3;
            };
            default -> throw new IllegalArgumentException("unknown fcn " + n);
        };
    }

    /* ------------------------------------------------------------------ */
    /* The runner                                                          */
    /* ------------------------------------------------------------------ */

    /** Words of a line as istringstream >> reads them. */
    private static final class Words {
        private final String[] w;
        private int i;

        Words(String s) {
            final String t = s.strip();
            w = t.isEmpty() ? new String[0] : t.split("[ \t]+");
        }

        String next() {
            return i < w.length ? w[i++] : "";
        }

        int nextInt() {
            final String s = next();
            return s.isEmpty() ? 0 : atoi(s);
        }

        double nextDouble() {
            final String s = next();
            return s.isEmpty() ? 0 : Double.parseDouble(s);
        }
    }

    private static int atoi(String s) {
        int i = 0;
        boolean neg = false;
        if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) neg = s.charAt(i++) == '-';
        int v = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) v = 10 * v + (s.charAt(i++) - '0');
        return neg ? -v : v;
    }

    private TMinuit make(TMinuit t) {
        t.setOutput(out);
        t.setErrorOutput(err);
        t.setInput(InputStream.nullInputStream());
        return t;
    }

    private void run(Reader script) throws IOException {
        final BufferedReader in = new BufferedReader(script);
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            out.printf("@ %s\n", line);
            out.flush();
            final int sp = line.indexOf(' ');
            final String op = sp < 0 ? line : line.substring(0, sp);
            final String rest = sp < 0 ? "" : line.substring(sp + 1);
            final Words ss = new Words(rest);
            switch (op) {
                case "new" -> {
                    final String n = ss.next();
                    final String f = ss.next();
                    m = make(n.equals("default") ? new TMinuit() : new TMinuit(atoi(n)));
                    m.SetFCN(byName(f));
                    nGrad = nFlag1 = nFlag3 = 0;
                }
                case "graphics" -> m.SetGraphicsMode(ss.nextInt() != 0);
                case "parm", "define" -> {
                    final int k = ss.nextInt();
                    String name = ss.next();
                    final double v = ss.nextDouble(), s = ss.nextDouble(), lo = ss.nextDouble(), hi = ss.nextDouble();
                    if (name.equals("-")) name = "";
                    int ierflg;
                    if (op.equals("parm")) {
                        final int[] e = {0};
                        m.mnparm(k, name, v, s, lo, hi, e);
                        ierflg = e[0];
                    } else {
                        ierflg = m.DefineParameter(k, name, v, s, lo, hi);
                    }
                    out.printf("ierflg %d\n", ierflg);
                }
                case "cmd" -> {
                    final int st = m.Command(rest);
                    out.printf("<< status %d\n", st);
                }
                case "excm" -> {
                    final String c = ss.next();
                    final int n = ss.nextInt();
                    final double[] a = new double[40];
                    for (int i = 0; i < n; i++) a[i] = ss.nextDouble();
                    final int[] ierflg = {0};
                    m.mnexcm(c, a, n, ierflg);
                    out.printf("ierflg %d\n", ierflg[0]);
                }
                case "dump" -> dump(rest);
                case "pout" -> {
                    final int k = ss.nextInt();
                    final String[] name = {""};
                    final double[] val = {0}, er = {0}, lo = {0}, hi = {0};
                    final int[] iuint = {0};
                    m.mnpout(k, name, val, er, lo, hi, iuint);
                    out.printf("pout '%s' iuint %d\n", name[0], iuint[0]);
                    H("  val", val[0]);
                    H("  err", er[0]);
                    H("  lo", lo[0]);
                    H("  hi", hi[0]);
                }
                case "emat" -> {
                    final int nd = ss.nextInt();
                    final double[] e = new double[400];
                    m.mnemat(e, nd);
                    for (int i = 0; i < nd * nd; i++) H("  emat" + i, e[i]);
                }
                case "nums" -> out.printf("pars %d fixed %d free %d\n", m.GetNumPars(), m.GetNumFixedPars(), m.GetNumFreePars());
                case "fix" -> out.printf("fix -> %d\n", m.FixParameter(ss.nextInt()));
                case "release" -> out.printf("release -> %d\n", m.Release(ss.nextInt()));
                case "errdef" -> out.printf("errdef -> %d\n", m.SetErrorDef(ss.nextDouble()));
                case "printlevel" -> out.printf("printlevel -> %d\n", m.SetPrintLevel(ss.nextInt()));
                case "migrad" -> out.printf("migrad -> %d\n", m.Migrad());
                case "contour" -> {
                    final int n = ss.nextInt(), a = ss.nextInt(), b = ss.nextInt();
                    final Object g = m.Contour(n, a, b);
                    out.printf("contour %s status %d\n", g != null ? "graph" : "null", m.GetStatus());
                }
                case "mncont" -> {
                    final int a = ss.nextInt(), b = ss.nextInt(), n = ss.nextInt();
                    final double[] x = new double[120], y = new double[120];
                    final int[] ierrf = {0};
                    m.mncont(a, b, n, x, y, ierrf);
                    out.printf("mncont ierrf %d\n", ierrf[0]);
                    for (int i = 0; i < n; i++) {
                        H("  x", x[i]);
                        H("  y", y[i]);
                    }
                }
                case "help" -> m.mnhelp(rest);
                case "unpt" -> out.printf("unpt %d\n", m.mnunpt(rest) ? 1 : 0);
                case "pars" -> {
                    final int[] icondn = {-1};
                    m.mnpars(rest, icondn);
                    out.printf("pars icondn %d\n", icondn[0]);
                }
                case "counts" -> out.printf("grad %d flag1 %d flag3 %d\n", nGrad, nFlag1, nFlag3);
                default -> throw new IllegalArgumentException("unknown op " + op);
            }
            out.flush();
        }
    }

    /** Runs a scenario: what TMinuit prints to out, its warnings to err. */
    public static void run(Reader script, PrintStream out, PrintStream err) throws IOException {
        TMinuit.resetRandomSeed();
        new TMinuitScript(out, err).run(script);
        out.flush();
        err.flush();
    }

    public static void main(String[] args) throws IOException {
        final PrintStream o = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false, StandardCharsets.UTF_8);
        final PrintStream e = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, StandardCharsets.UTF_8);
        try (Reader r = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
            run(r, o, e);
        }
    }
}
