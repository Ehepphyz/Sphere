package com.sphere.core.commands;

import com.sphere.components.rootview.RootFitter;
import com.sphere.components.rootview.RootFormula;
import com.sphere.components.rootview.RootPlotsPanel;
import com.sphere.core.minuit.TMinuit;
import com.sphere.core.minuit.TMinuitGraph;
import com.sphere.core.minuit.validation.TMinuitTests;
import com.sphere.core.minuit2.FCNBase;
import com.sphere.core.minuit2.FunctionMinimum;
import com.sphere.core.minuit2.MnMigrad;
import com.sphere.core.minuit2.MnStrategy;
import com.sphere.core.minuit2.MnUserParameterState;
import com.sphere.core.minuit2.MnUserParameters;
import com.sphere.core.minuit2.PdgFormat;
import com.sphere.core.hepmc3.cxx.CFormat;
import com.sphere.core.hepmc3.cxx.NativeMath;
import com.sphere.utils.AppLogger;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ROOT's first minimizer, TMinuit (math/minuit, the MINUIT of F. James), from
 * Sphere's console: a function written as a formula is handed to TMinuit and
 * driven with MINUIT's own command language (MIGRAD, SIMPLEX, MINOS, SCAN,
 * CONTOUR, SET and SHOW...), as gMinuit-&gt;Command() does in a ROOT macro, with
 * MINUIT's printout as it is. The contours and scans it makes reach the Plots
 * tab; the result is also given in words and with its errors rounded as the
 * PDG does; TMinuit and Minuit2 can be run side by side on the same function;
 * and the port is checked against ROOT's TMinuit bit for bit.
 */
public final class MinuitCommands {

    private MinuitCommands() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** The TMinuit of the last fit, for :minuit cmd and :minuit contour. */
    private static TMinuit current;
    private static List<String> currentNames = List.of();
    private static boolean graphics = true;
    private static Object lastPlot;

    /** Words of the command line: plain words (a quoted formula is one), and --options with their value. */
    private static final class Args {
        final List<String> plain = new ArrayList<>();
        final Map<String, String> opts = new LinkedHashMap<>();
        final String raw;

        Args(String input) {
            raw = input;
            final List<String> w = new ArrayList<>();
            final StringBuilder cur = new StringBuilder();
            boolean quoted = false;
            boolean any = false;
            for (int i = 0; i < input.length(); i++) {
                final char c = input.charAt(i);
                if (c == '"') {
                    quoted = !quoted;
                    any = true;
                } else if (Character.isWhitespace(c) && !quoted) {
                    if (any) w.add(cur.toString());
                    cur.setLength(0);
                    any = false;
                } else {
                    cur.append(c);
                    any = true;
                }
            }
            if (any) w.add(cur.toString());
            for (int k = 0; k < w.size(); k++) {
                final String s = w.get(k);
                if (s.startsWith("--") && s.length() > 2) {
                    final String key = s.substring(2).toLowerCase(Locale.ROOT);
                    if (k + 1 < w.size() && !w.get(k + 1).startsWith("--")) {
                        final String prev = opts.get(key);
                        opts.put(key, prev == null ? w.get(++k) : prev + " " + w.get(++k));
                    } else {
                        opts.put(key, "true");
                    }
                } else {
                    plain.add(s);
                }
            }
        }

        boolean has(String key) {
            return opts.containsKey(key);
        }

        double opt(String key, double fallback) {
            final String v = opts.get(key);
            return v == null || v.equals("true") ? fallback : Double.parseDouble(v);
        }

        double num(int k, double fallback) {
            return k < plain.size() ? Double.parseDouble(plain.get(k)) : fallback;
        }
    }

    @FunctionalInterface
    private interface Body {
        void run(Args a) throws Exception;
    }

    /** A command that also talks to ROOT's interpreter, through the context of the console. */
    @FunctionalInterface
    private interface ContextBody {
        void run(Args a, CommandExecutionContext c) throws Exception;
    }

    private static void reg(String sub, String description, Body body) {
        regContext(sub, description, (a, c) -> body.run(a));
    }

    private static void regContext(String sub, String description, ContextBody body) {
        final String name = ":minuit " + sub;
        CommandDefinitions.register(name, description, (i, c) -> {
            try {
                body.run(new Args(Handlers.args(i, name)), c);
            } catch (Exception e) {
                AppLogger.error(name + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        });
    }

    static void register() {
        reg("help", "What the TMinuit commands do", a -> help());
        reg("fit", "Minimize a formula of parameters [0], [1]... with TMinuit, driven by MINUIT commands. Usage: :minuit fit "
            + "\"<formula>\" [start values] [--err ...] [--limit i:lo:hi] [--fix i] [--up u] [--strategy s] [--grad] "
            + "[--print p] [--do \"MIGRAD; MINOS\"]", MinuitCommands::fit);
        reg("cmd", "Any MINUIT command on the last fit (1-based parameter numbers, as MINUIT counts). Usage: :minuit cmd "
            + "<command>, e.g. SHOW COV, MINOS, SCAN 1, CONTOUR 1 2, MNCONTOUR 1 2 30, IMPROVE, SEEK, HELP", MinuitCommands::cmd);
        reg("contour", "The contour of two parameters at FCNmin + UP, drawn in Plots. Usage: :minuit contour <i> <j> [points]",
            MinuitCommands::contour);
        reg("plot", "Where MINUIT's scans and contours go: the Plots tab (on, ROOT's graphics mode) or MINUIT's own "
            + "line-printer plots in the console (off). Usage: :minuit plot [on|off]", MinuitCommands::plot);
        reg("compare", "TMinuit and Minuit2 (MIGRAD) side by side on the same formula: values, errors, calls, and how far "
            + "apart. Usage: :minuit compare \"<formula>\" [start values] [--err ...] [--limit i:lo:hi] [--up u] [--strategy s]",
            MinuitCommands::compare);
        reg("validate", "Run the TMinuit probe and scenarios, ported, against ROOT's TMinuit outputs. Usage: "
            + ":minuit validate [run]", MinuitCommands::validate);
        regContext("use", "The viewer's fits (TH1::Fit...) through TMinuit, ROOT's \"Minuit\" minimizer type; --root tells "
            + "ROOT's own fits too. Usage: :minuit use [--strategy s] [--tolerance t] [--root]  (back: :minuit2 default "
            + "--algo migrad)", MinuitCommands::use);
    }

    /** ROOT::Math::MinimizerOptions::SetDefaultMinimizer("Minuit"): the fits of the viewer, and of ROOT when asked. */
    private static void use(Args a, CommandExecutionContext c) {
        RootFitter.Options.setDefaults("TMinuit", (int) a.opt("strategy", -1), a.opt("tolerance", -1));
        AppLogger.result(String.format(Locale.ROOT, "The viewer's fits use TMinuit (ROOT's \"Minuit\" type): MIGRAD, "
            + "HESSE, MINOS with E; strategy %d, tolerance %s.", RootFitter.Options.defaultStrategy(),
            RootFitter.Options.defaultTolerance()));
        if (a.has("root")) {
            Handlers.cling(c, "ROOT::Math::MinimizerOptions::SetDefaultMinimizer(\"Minuit\"); "
                + "ROOT::Math::MinimizerOptions::SetDefaultStrategy(" + RootFitter.Options.defaultStrategy() + "); "
                + "ROOT::Math::MinimizerOptions::SetDefaultTolerance(" + RootFitter.Options.defaultTolerance() + "); "
                + "ROOT::Math::MinimizerOptions::DefaultMinimizerType()");
        }
    }

    private static void help() {
        AppLogger.result("TMinuit, ROOT's first minimizer (math/minuit), in Java bit for bit; MINUIT's command language:");
        AppLogger.raw("  :minuit fit \"<formula>\" [start] [--err e,...] [--limit i:lo:hi] [--fix i] [--up u] [--strategy s]");
        AppLogger.raw("                [--grad] [--print -1..3] [--do \"MIGRAD; HESSE; MINOS\"]   (default --do MIGRAD)");
        AppLogger.raw("  :minuit cmd <MINUIT command>    SHOW COV | MINOS | SCAN 1 | CONTOUR 1 2 | MNCONTOUR 1 2 30 | IMPROVE |");
        AppLogger.raw("                                  SEEK | SIMPLEX | SET STR 2 | SET ERR 0.5 | FIX 2 | RELEASE 2 | HELP ...");
        AppLogger.raw("  :minuit contour <i> <j> [points]  the 1-sigma contour (FCNmin + UP) of two parameters, in Plots");
        AppLogger.raw("  :minuit plot [on|off]            scans and contours to Plots (on) or as MINUIT's printer plots (off)");
        AppLogger.raw("  :minuit compare \"<formula>\" ...  TMinuit and Minuit2 on the same function: do they agree?");
        AppLogger.raw("  :minuit validate [run]           the port against ROOT's TMinuit, every printed character and bit");
        AppLogger.raw("  :minuit use [--strategy s] [--root]  the viewer's fits (and ROOT's with --root) through TMinuit;");
        AppLogger.raw("                                   back to Minuit2: :minuit2 default --algo migrad");
        AppLogger.raw("  Parameters are 0-based in Sphere's options (--limit 0:..., contour 0 1) and 1-based in MINUIT's");
        AppLogger.raw("  commands (SCAN 1, FIX 2), as each counts them. Minuit2: :minuit2 help.");
    }

    /* ---- TMinuit's output to the console ------------------------------------------------------- */

    /** A stream whose lines go to the console: as they are, or as warnings. */
    private static PrintStream console(boolean warnings) {
        return new PrintStream(new OutputStream() {
            private final StringBuilder line = new StringBuilder();

            @Override
            public void write(int b) {
                if (b == '\n') {
                    final String l = line.toString();
                    line.setLength(0);
                    if (warnings) AppLogger.warn(l);
                    else AppLogger.raw(l);
                } else if (b != '\r') {
                    line.append((char) (b & 0xFF));
                }
            }
        }, true, StandardCharsets.ISO_8859_1);
    }

    private static final PrintStream SILENT = new PrintStream(OutputStream.nullOutputStream(), false, StandardCharsets.ISO_8859_1);

    /** The FCN of a formula of n parameters, with its gradient (automatic differentiation) when MINUIT asks for it. */
    private static TMinuit.FCN fcn(RootFormula f, int n, boolean gradient) {
        final double[] vars = new double[4];
        return (npar, gin, fval, u, flag) -> {
            final double[] p = Arrays.copyOf(u, n);
            fval[0] = f.eval(vars, p);
            if (gradient && flag == 2) {
                final double[] g = new double[n];
                f.evalGradient(vars, p, g);
                System.arraycopy(g, 0, gin, 0, n);
            }
        };
    }

    private static double[] list(String s) {
        final String[] w = s.trim().split("[,\\s]+");
        final double[] v = new double[w.length];
        for (int i = 0; i < w.length; i++) v[i] = Double.parseDouble(w[i]);
        return v;
    }

    /** --limit i:lo:hi ...: TMinuit's limits are two-sided, as MINUIT's are. */
    private static double[][] limits(Args a, int n) {
        final double[][] l = new double[n][2];
        if (!a.has("limit")) return l;
        for (String s : a.opts.get("limit").split("\\s+")) {
            final String[] p = s.split(":");
            final int i = Integer.parseInt(p[0]);
            if (i < 0 || i >= n) throw new IllegalArgumentException("no parameter " + i + " (they are 0 to " + (n - 1) + ")");
            if (p.length < 3 || p[1].isEmpty() || p[2].isEmpty()) {
                throw new IllegalArgumentException("TMinuit takes two limits (--limit " + i + ":lo:hi); Minuit2 takes one "
                    + "(:minuit2 minimize ... --limit " + i + ":lo:)");
            }
            l[i][0] = Double.parseDouble(p[1]);
            l[i][1] = Double.parseDouble(p[2]);
        }
        return l;
    }

    /** What MNCOMD's ICONDN means. */
    private static String status(int st) {
        return switch (st) {
            case 0 -> "done";
            case 1 -> "blank command, ignored";
            case 2 -> "command line unreadable, ignored";
            case 3 -> "unknown command, ignored";
            case 4 -> "abnormal termination (e.g. MIGRAD not converged)";
            case 5 -> "PARAMETER: definitions are given with :minuit fit";
            case 6 -> "SET INPUT: MINUIT reads no files here";
            case 7 -> "SET TITLE: no title is read here";
            case 8 -> "SET COVARIANCE: no matrix is read here";
            case 10 -> "END";
            case 11 -> "EXIT / STOP";
            case 12 -> "RETURN";
            default -> "status " + st;
        };
    }

    private static void run(TMinuit m, String command) {
        final int st = m.Command(command);
        if (st != 0 && st < 10) AppLogger.warn(command.trim() + ": " + status(st) + ".");
    }

    private static void fit(Args a) {
        if (a.plain.isEmpty()) {
            Handlers.usage(":minuit fit \"<formula of [0], [1]...>\" [start values] [--err ...] [--limit i:lo:hi] [--fix i] "
                + "[--up u] [--strategy s] [--grad] [--print p] [--do \"MIGRAD; MINOS\"]");
            return;
        }
        final RootFormula f = RootFormula.parse(a.plain.get(0));
        final int n = Math.max(f.parameters(), a.plain.size() - 1);
        if (n == 0) {
            AppLogger.error("The formula has no parameter: write them [0], [1]... or [name].");
            return;
        }
        final double[] err = a.has("err") ? list(a.opts.get("err")) : null;
        final double[][] lim = limits(a, n);
        final List<String> names = names(f, n);
        final TMinuit m = new TMinuit(n);
        m.setOutput(console(false));
        m.setErrorOutput(console(true));
        m.setInput(InputStream.nullInputStream());
        m.SetGraphicsMode(graphics);
        m.SetFCN(fcn(f, n, a.has("grad")));
        m.SetPrintLevel((int) a.opt("print", 0));
        for (int i = 0; i < n; i++) {
            final double v = a.num(i + 1, 0.);
            final double e = err != null && i < err.length ? err[i] : v != 0 ? 0.1 * Math.abs(v) : 0.1;
            if (m.DefineParameter(i, names.get(i), v, e, lim[i][0], lim[i][1]) != 0) {
                AppLogger.error("TMinuit refused parameter " + i + " (" + names.get(i) + ").");
                return;
            }
        }
        if (a.has("fix")) for (String i : a.opts.get("fix").split("[,\\s]+")) m.FixParameter(Integer.parseInt(i));
        if (a.has("up")) m.SetErrorDef(a.opt("up", 1.0));
        if (a.has("strategy")) run(m, "SET STR " + (int) a.opt("strategy", 1));
        if (a.has("grad")) run(m, "SET GRAD");
        current = m;
        currentNames = names;
        lastPlot = m.GetPlot();
        final long t0 = System.nanoTime();
        for (String c : a.opts.getOrDefault("do", "MIGRAD").split(";")) {
            if (!c.isBlank()) run(m, c.trim());
        }
        summary(m, f.text(), (System.nanoTime() - t0) / 1e6);
        draw(m, a.opts.getOrDefault("do", "MIGRAD"));
    }

    private static List<String> names(RootFormula f, int n) {
        final List<String> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(f.parameterName(i));
        return RootFitter.uniqueNames(l);
    }

    /** The result in words: each parameter with its errors rounded as the PDG does, and what the covariance is worth. */
    private static void summary(TMinuit m, String what, double millis) {
        final double[] fmin = {0}, fedm = {0}, errdef = {0};
        final int[] npari = {0}, nparx = {0}, istat = {0};
        m.mnstat(fmin, fedm, errdef, npari, nparx, istat);
        AppLogger.result(CFormat.sprintf("TMinuit on %s: FCN = %.10g, EDM = %.3g, UP = %g, %d calls in %.1f ms;",
            what, fmin[0], fedm[0], errdef[0], m.fNfcn, millis));
        for (int i = 0; i < nparx[0]; i++) {
            final String[] name = {""};
            final double[] v = {0}, e = {0}, lo = {0}, hi = {0}, ep = {0}, en = {0}, eparab = {0}, gcc = {0};
            final int[] iint = {0};
            m.mnpout(i, name, v, e, lo, hi, iint);
            if (iint[0] < 0) continue;
            m.mnerrs(i, ep, en, eparab, gcc);
            final String value;
            if (iint[0] == 0) value = String.format(Locale.ROOT, "%.6g  (fixed)", v[0]);
            else if (ep[0] != 0 && en[0] != 0) value = PdgFormat.asymmetric(v[0], en[0], ep[0]) + "  (Minos)";
            else value = PdgFormat.format(v[0], e[0]);
            AppLogger.raw(AppLogger.plusMinus(String.format(Locale.ROOT, "  %-12s %s%s", name[0], value,
                lo[0] != 0 || hi[0] != 0 ? CFormat.sprintf("   in [%g, %g]", lo[0], hi[0]) : "")));
        }
        AppLogger.raw("  covariance: " + switch (istat[0]) {
            case 3 -> "accurate (full matrix, positive-definite)";
            case 2 -> "forced positive-definite: the errors are not to be trusted";
            case 1 -> "approximate only (MIGRAD's update, not HESSE's)";
            default -> "not calculated";
        });
    }

    /** A scan or contour TMinuit made in graphics mode (gMinuit-&gt;GetPlot()), to the Plots tab. */
    private static void draw(TMinuit m, String commands) {
        final Object p = m.GetPlot();
        if (!graphics || p == null || p == lastPlot || !(p instanceof TMinuitGraph g) || g.GetN() == 0) return;
        lastPlot = p;
        final String c = commands.trim().toUpperCase(Locale.ROOT);
        final String x = c.contains("SCA") ? "parameter" : "first parameter";
        final String y = c.contains("SCA") ? "FCN" : "second parameter";
        if (toPlots(x, y, g)) AppLogger.info("TMinuit's plot (" + g.GetN() + " points) -> Plots.");
    }

    /** A graph to the Plots tab; false (and said) where there is none, Sphere running without its window. */
    private static boolean toPlots(String x, String y, TMinuitGraph g) {
        try {
            RootPlotsPanel.instance().showCurve(x, y, g.GetX(), g.GetY());
            return true;
        } catch (java.awt.HeadlessException | IllegalStateException noWindow) {
            AppLogger.info("No Plots tab without Sphere's window: ':minuit plot off' prints MINUIT's own plots instead.");
            return false;
        }
    }

    private static boolean need() {
        if (current == null) AppLogger.error("No TMinuit yet: start with :minuit fit \"<formula>\" ...");
        return current != null;
    }

    private static void cmd(Args a) {
        if (!need()) return;
        if (a.raw.isBlank()) {
            Handlers.usage(":minuit cmd <MINUIT command> (HELP lists them)");
            return;
        }
        current.SetGraphicsMode(graphics);
        run(current, a.raw);
        draw(current, a.raw);
    }

    private static void contour(Args a) {
        if (!need()) return;
        if (a.plain.size() < 2) {
            Handlers.usage(":minuit contour <i> <j> [points]   (0-based parameters)");
            return;
        }
        final int i = Integer.parseInt(a.plain.get(0));
        final int j = Integer.parseInt(a.plain.get(1));
        final int np = (int) a.num(2, 20);
        final Object g = current.Contour(np, i, j);
        if (!(g instanceof TMinuitGraph tg)) {
            AppLogger.warn("TMinuit found no contour (status " + current.GetStatus() + "): are both parameters variable, "
                + "and the fit converged?");
            return;
        }
        final String xi = i < currentNames.size() ? currentNames.get(i) : "p" + i;
        final String yj = j < currentNames.size() ? currentNames.get(j) : "p" + j;
        AppLogger.result("The contour of " + xi + " and " + yj + " at FCNmin + UP: " + (tg.GetN() - 1) + " points"
            + (toPlots(xi, yj, tg) ? " (closed) -> Plots." : "."));
    }

    private static void plot(Args a) {
        if (!a.plain.isEmpty()) graphics = !a.plain.get(0).equalsIgnoreCase("off");
        AppLogger.result(graphics ? "MINUIT's scans and contours go to the Plots tab (ROOT's graphics mode)."
            : "MINUIT prints its scans and contours as line-printer plots in the console.");
    }

    /**
     * TMinuit and Minuit2 on the same function, both by MIGRAD (which ends with HESSE at strategy 1): the
     * parameters, their errors, the calls, and the largest shift in units of the error. ROOT 6 made Minuit2 the
     * default: this says whether a result made with the old one stands.
     */
    private static void compare(Args a) {
        if (a.plain.isEmpty()) {
            Handlers.usage(":minuit compare \"<formula>\" [start values] [--err ...] [--limit i:lo:hi] [--up u] [--strategy s]");
            return;
        }
        final RootFormula f = RootFormula.parse(a.plain.get(0));
        final int n = Math.max(f.parameters(), a.plain.size() - 1);
        if (n == 0) {
            AppLogger.error("The formula has no parameter: write them [0], [1]... or [name].");
            return;
        }
        final double up = a.opt("up", 1.0);
        final int strategy = (int) a.opt("strategy", 1);
        final double[] err = a.has("err") ? list(a.opts.get("err")) : null;
        final double[][] lim = limits(a, n);
        final List<String> names = names(f, n);
        final double[] start = new double[n];
        final double[] step = new double[n];
        for (int i = 0; i < n; i++) {
            start[i] = a.num(i + 1, 0.);
            step[i] = err != null && i < err.length ? err[i] : start[i] != 0 ? 0.1 * Math.abs(start[i]) : 0.1;
        }
        // TMinuit, quiet
        final TMinuit m = new TMinuit(n);
        m.setOutput(SILENT);
        m.setErrorOutput(SILENT);
        m.setInput(InputStream.nullInputStream());
        m.SetFCN(fcn(f, n, false));
        m.SetPrintLevel(-1);
        for (int i = 0; i < n; i++) m.DefineParameter(i, names.get(i), start[i], step[i], lim[i][0], lim[i][1]);
        m.SetErrorDef(up);
        m.Command("SET STR " + strategy);
        long t0 = System.nanoTime();
        final int st1 = m.Migrad();
        final double ms1 = (System.nanoTime() - t0) / 1e6;
        final double[] fmin = {0}, fedm = {0}, errdef = {0};
        final int[] npari = {0}, nparx = {0}, istat = {0};
        m.mnstat(fmin, fedm, errdef, npari, nparx, istat);
        final double[] v1 = new double[n], e1 = new double[n];
        for (int i = 0; i < n; i++) {
            final double[] v = {0}, e = {0};
            m.GetParameter(i, v, e);
            v1[i] = v[0];
            e1[i] = e[0];
        }
        // Minuit2
        final double[] vars = new double[4];
        final FCNBase fcn = new FCNBase() {
            @Override
            public double value(double[] p) {
                return f.eval(vars, p);
            }

            @Override
            public double up() {
                return up;
            }
        };
        final MnUserParameters upar = new MnUserParameters();
        for (int i = 0; i < n; i++) {
            upar.add(names.get(i), start[i], step[i]);
            if (lim[i][0] != 0 || lim[i][1] != 0) upar.setLimits(i, lim[i][0], lim[i][1]);
        }
        t0 = System.nanoTime();
        final FunctionMinimum fm = new MnMigrad(fcn, new MnUserParameterState(upar), new MnStrategy(strategy)).minimize(0, 0.1);
        final double ms2 = (System.nanoTime() - t0) / 1e6;
        final MnUserParameterState us = fm.userState();

        AppLogger.result("TMinuit and Minuit2 (MIGRAD, strategy " + strategy + ") on " + f.text() + ":");
        AppLogger.raw(String.format(Locale.ROOT, "  %-12s %-28s %-28s %s", "parameter", "TMinuit", "Minuit2", "shift / error"));
        double worst = 0;
        double worstErr = 0;
        for (int i = 0; i < n; i++) {
            final double v2 = us.value(i);
            final double e2 = us.error(i);
            final double sigma = Math.max(e1[i], e2);
            final double shift = sigma > 0 ? Math.abs(v1[i] - v2) / sigma : 0;
            worst = Math.max(worst, shift);
            if (e1[i] > 0 && e2 > 0) worstErr = Math.max(worstErr, Math.abs(Math.log(e1[i] / e2)));
            AppLogger.raw(AppLogger.plusMinus(String.format(Locale.ROOT, "  %-12s %-28s %-28s %.3g", names.get(i),
                PdgFormat.format(v1[i], e1[i]), PdgFormat.format(v2, e2), shift)));
        }
        AppLogger.raw(String.format(Locale.ROOT, "  %-12s %-28s %-28s", "FCN min", String.format(Locale.ROOT, "%.10g", fmin[0]),
            String.format(Locale.ROOT, "%.10g", fm.fval())));
        // TMinuit's EDM is g'(2/G)g / 2 with its matrix holding 2/G, Minuit2's g'(1/G)g / 2: twice as large by definition
        AppLogger.raw(String.format(Locale.ROOT, "  %-12s %-28s %-28s %s", "EDM", String.format(Locale.ROOT, "%.3g", fedm[0]),
            String.format(Locale.ROOT, "%.3g", fm.edm()), "(TMinuit's is twice Minuit2's by definition)"));
        AppLogger.raw(String.format(Locale.ROOT, "  %-12s %-28s %-28s", "calls", m.fNfcn + String.format(Locale.ROOT,
            " (%.1f ms)", ms1), fm.nfcn() + String.format(Locale.ROOT, " (%.1f ms)", ms2)));
        AppLogger.raw(String.format(Locale.ROOT, "  %-12s %-28s %-28s", "status", st1 == 0 ? "converged" : "MIGRAD status " + st1,
            fm.isValid() ? "valid" : "not valid"));
        final boolean bothOk = st1 == 0 && fm.isValid();
        final String verdict;
        if (!bothOk) {
            verdict = "At least one of them did not converge: the comparison says little; try :minuit2 minimize ... --auto.";
        } else if (worst < 0.01 && worstErr < 0.01) {
            verdict = "They agree to a hundredth of an error, errors included: either result can be quoted.";
        } else if (worst < 0.1 && worstErr < 0.1) {
            verdict = String.format(Locale.ROOT, "They agree within %.2g of an error (errors within %.0f%%): the same minimum, "
                + "found along different paths.", worst, 100 * (Math.exp(worstErr) - 1));
        } else {
            verdict = String.format(Locale.ROOT, "They differ by %.2g errors (errors by %.0f%%): the minimum is not well "
                + "determined (flat or multiple minima); look at it with :minuit2 minimize ... --auto or --multistart.",
                worst, 100 * (Math.exp(worstErr) - 1));
        }
        if (bothOk && worst < 0.1 && worstErr < 0.1) AppLogger.success(verdict);
        else AppLogger.warn(verdict);
    }

    private static void validate(Args a) throws Exception {
        final TMinuitTests.References refs = TMinuitTests.References.packaged(TMinuitTests.platform());
        if (refs == null) {
            AppLogger.error("No C++ outputs for " + TMinuitTests.platform() + " in the jar (refdata/tminuit-"
                + TMinuitTests.platform() + ".zip).");
            return;
        }
        AppLogger.info("The TMinuit probe and scenarios, ported, against ROOT's TMinuit (" + refs.platform() + "); "
            + NativeMath.describe() + ":");
        int ok = 0;
        int n = 0;
        final long t0 = System.nanoTime();
        for (TMinuitTests.Outcome o : TMinuitTests.compareAll(a.plain.isEmpty() ? "" : a.plain.get(0), refs)) {
            n++;
            if (o.passed()) ok++;
            for (String l : TMinuitTests.line(o).split("\n")) AppLogger.raw("  " + l);
        }
        final String summary = String.format(Locale.ROOT, "%d of %d agree with ROOT's TMinuit, character for character "
            + "(every printout, plot and warning; the results as their 64 bits), in %.1f s.", ok, n, (System.nanoTime() - t0) / 1e9);
        if (ok == n && n > 0) AppLogger.success(summary);
        else AppLogger.warn(summary);
    }
}
