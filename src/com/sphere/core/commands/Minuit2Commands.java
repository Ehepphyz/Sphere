package com.sphere.core.commands;

import com.sphere.components.rootview.RootFitter;
import com.sphere.components.rootview.RootFormula;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.NativeMath;
import com.sphere.core.minuit2.Autopilot;
import com.sphere.core.minuit2.ContoursError;
import com.sphere.core.minuit2.FCNBase;
import com.sphere.core.minuit2.FCNGradientBase;
import com.sphere.core.minuit2.FunctionMinimum;
import com.sphere.core.minuit2.GradientCalculator;
import com.sphere.core.minuit2.MinosError;
import com.sphere.core.minuit2.Minuit2Citations;
import com.sphere.core.minuit2.MnContours;
import com.sphere.core.minuit2.MnHesse;
import com.sphere.core.minuit2.MnMigrad;
import com.sphere.core.minuit2.MnMinimize;
import com.sphere.core.minuit2.MnMinos;
import com.sphere.core.minuit2.MnParameterScan;
import com.sphere.core.minuit2.MnParameterTransformation;
import com.sphere.core.minuit2.MnPlot;
import com.sphere.core.minuit2.MnPrint;
import com.sphere.core.minuit2.MnScan;
import com.sphere.core.minuit2.MnSimplex;
import com.sphere.core.minuit2.MnStrategy;
import com.sphere.core.minuit2.MnUserParameterState;
import com.sphere.core.minuit2.MnUserParameters;
import com.sphere.core.minuit2.ModularFunctionMinimizer;
import com.sphere.core.minuit2.VariableMetricMinimizer;
import com.sphere.core.minuit2.validation.Minuit2Tests;
import com.sphere.core.minuit2.validation.Programs;
import com.sphere.utils.AppLogger;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The console's Minuit2: Sphere's Java translation of ROOT's Minuit2, bit for
 * bit the C++, with ROOT's algorithms (Migrad, Simplex, Minimize, Scan,
 * Fumili, BFGS), Hesse, Minos, contours and scans on any function written
 * as a formula of parameters, an autopilot that does what a failing fit
 * calls for and says in words what the result means, the defaults of the
 * fits of the ROOT viewer, and the check of the translation against the C++.
 */
public final class Minuit2Commands {

    private Minuit2Commands() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** The last minimization: its function and result, for hesse, minos, profile, contour and scan. */
    private static FCNBase lastFcn;
    private static FunctionMinimum lastMin;
    private static String lastText = "";
    private static int printLevel = 0;

    /** Minuit2's messages ([Warn] ...) to the console. */
    private static void line(String l) {
        if (l.startsWith("[Error]")) AppLogger.error(l);
        else if (l.startsWith("[Warn]")) AppLogger.warn(l);
        else AppLogger.raw(l);
    }

    @FunctionalInterface
    private interface Body {
        void run(Args a) throws Exception;
    }

    private static void reg(String sub, String description, Body body) {
        final String name = ":minuit2 " + sub;
        CommandDefinitions.register(name, description, (i, c) -> {
            final int prev = MnPrint.setGlobalLevel(printLevel);
            MnPrint.setSink(Minuit2Commands::line);
            try {
                body.run(new Args(Handlers.args(i, name)));
            } catch (Exception e) {
                AppLogger.error(name + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                MnPrint.setSink(null);
                MnPrint.setGlobalLevel(prev);
            }
        });
    }

    static void register() {
        reg("help", "What the Minuit2 commands do", a -> help());
        reg("version", "The version of Sphere's Minuit2 and what it translates", a -> AppLogger.raw(Minuit2Citations.versionLine()));
        reg("info", "The C library functions and the long double reproduced, the defaults of the fits", a -> info());
        reg("minimize", "Minimize a formula of parameters [0], [1]... or [name]. Usage: :minuit2 minimize \"<formula>\" [start values] "
            + "[--err e0,e1] [--limit i:lo:hi] [--fix i,j] [--up 1] [--algo migrad|simplex|minimize|scan|bfgs] [--strategy s] "
            + "[--tolerance t] [--grad] [--minos] [--auto] [--multistart k]", Minuit2Commands::minimize);
        reg("hesse", "Hesse on the last minimum. Usage: :minuit2 hesse [--strategy s]", Minuit2Commands::hesse);
        reg("minos", "Minos errors of the last minimum. Usage: :minuit2 minos [parameter] [--up u]", Minuit2Commands::minos);
        reg("profile", "The profile of a parameter (the others minimized at each point). Usage: :minuit2 profile <parameter> "
            + "[--points n] [--sigma k]", Minuit2Commands::profile);
        reg("contour", "The contour of two parameters at FCNmin + up. Usage: :minuit2 contour <i> <j> [--points n] [--up u]",
            Minuit2Commands::contour);
        reg("scan", "The function along a parameter, the others held. Usage: :minuit2 scan <parameter> [--steps n] "
            + "[--low a] [--high b]", Minuit2Commands::scan);
        reg("default", "The minimizer of the ROOT viewer's fits (TH1::Fit...). Usage: :minuit2 default [--algo "
            + "migrad|simplex|minimize|scan|bfgs|fumili] [--strategy s] [--tolerance t]", Minuit2Commands::defaults);
        reg("longdouble", "The long double reproduced in the parameter transformations. Usage: :minuit2 longdouble "
            + "[x87|double|auto] (double: as ROOT built by MSVC)", Minuit2Commands::longDouble);
        reg("parallel", "Compute the numerical gradient on several threads (same values). Usage: :minuit2 parallel [on|off]",
            a -> {
                if (!a.plain.isEmpty()) GradientCalculator.setParallel(a.word(0, "off").equalsIgnoreCase("on"));
                AppLogger.result("Parallel numerical gradient: " + (GradientCalculator.isParallel() ? "on" : "off"));
            });
        reg("print", "Minuit2's print level for these commands (0 errors, 1 warnings, 2 info, 3 debug). Usage: :minuit2 print <level>",
            a -> {
                if (!a.plain.isEmpty()) printLevel = (int) a.num(0, 0);
                AppLogger.result("Minuit2 print level: " + printLevel);
            });
        reg("run", "Run one of ROOT's Minuit2 test programs (ported) and show what it prints. Usage: :minuit2 run <name>",
            Minuit2Commands::run);
        reg("validate", "Run ROOT's Minuit2 test programs and the bit probe, ported, against the C++ outputs. Usage: "
            + ":minuit2 validate [test]", Minuit2Commands::validate);
        CommandDefinitions.register(":root minuit2 use", "ROOT's fits through Minuit2, with the settings of Sphere's "
            + "(as :minuit2 default). Usage: :root minuit2 use [--algo migrad|simplex|minimize|scan|fumili] [--strategy s] "
            + "[--tolerance t]", (i, c) -> {
                final Args a = new Args(Handlers.args(i, ":root minuit2 use"));
                defaults(a);
                final String header = header(c);
                if (header == null) return;
                Handlers.cling(c, "sphere::minuit2::use(\"" + cap(RootFitter.Options.defaultAlgorithm()) + "\", "
                    + RootFitter.Options.defaultStrategy() + ", " + RootFitter.Options.defaultTolerance() + ")");
            });
        CommandDefinitions.register(":root minuit2 crosscheck", "The same minimization by ROOT's Minuit2 (C++) and Sphere's "
            + "(Java), compared number for number. Usage: :root minuit2 crosscheck \"<formula of [0], [1]...>\" <start values> "
            + "[--algo a] [--strategy s] [--tolerance t] [--up u]", Minuit2Commands::crosscheck);
        reg("bib", "Write the BibTeX of Minuit and ROOT. Usage: :minuit2 bib <file.bib>", a -> {
            if (a.plain.isEmpty()) {
                Handlers.usage(":minuit2 bib <file.bib>");
                return;
            }
            final Path out = Handlers.resolve(a.plain.get(0)).toPath();
            Files.writeString(out, Minuit2Citations.bibtex(), StandardCharsets.UTF_8);
            AppLogger.result("3 references written to " + out);
        });
    }

    /* ---- arguments --------------------------------------------------------------- */

    /** Words (a quoted text is one word), --options with a value or alone. */
    private static final class Args {
        final List<String> plain = new ArrayList<>();
        final Map<String, String> opts = new LinkedHashMap<>();

        Args(String input) {
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

        String word(int k, String fallback) {
            return k < plain.size() ? plain.get(k) : fallback;
        }

        double num(int k, double fallback) {
            try {
                return k < plain.size() ? Double.parseDouble(plain.get(k)) : fallback;
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        double opt(String key, double fallback) {
            try {
                return opts.containsKey(key) ? Double.parseDouble(opts.get(key)) : fallback;
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        boolean has(String key) {
            return opts.containsKey(key);
        }
    }

    /* ---- commands ----------------------------------------------------------------- */

    private static void help() {
        AppLogger.result("Minuit2, ROOT's minimizer, in Java: " + Minuit2Citations.versionLine() + ".");
        AppLogger.raw("  minimize   :minuit2 minimize \"100*([1]-[0]^2)^2+(1-[0])^2\" -1.2 1   [--grad] [--minos] [--auto]");
        AppLogger.raw("             parameters [0], [1]... or [name]; --err, --limit i:lo:hi, --fix i, --up 0.5 for a -log L");
        AppLogger.raw("             --algo migrad|simplex|minimize|scan|bfgs, --strategy 0..3, --multistart k (spread starts)");
        AppLogger.raw("  then       :minuit2 hesse | minos [i] | profile <i> | contour <i> <j> | scan <i>");
        AppLogger.raw("  fits       the ROOT viewer's Fit (TH1::Fit, Fit Panel) uses Minuit2; :minuit2 default --algo fumili --strategy 2");
        AppLogger.raw("             fit options as ROOT: E Minos, G gradient (automatic differentiation of the formula), M retry");
        AppLogger.raw("  engine     :minuit2 info | longdouble x87|double | parallel on | print 1");
        AppLogger.raw("  trust      :minuit2 validate (ROOT's test programs and a bit probe against the C++) | run DemoGaussSim");
        AppLogger.raw("  references Citations > Citation Minuit2, or :minuit2 bib <file>");
    }

    private static void info() {
        AppLogger.result(Minuit2Citations.versionLine());
        AppLogger.raw("  C library functions : " + NativeMath.describe());
        AppLogger.raw("  long double         : " + MnParameterTransformation.longDouble()
            + " (x87: GCC/Clang on x86-64; double: MSVC, Apple Silicon)");
        AppLogger.raw("  parallel gradient   : " + (GradientCalculator.isParallel() ? "on" : "off"));
        AppLogger.raw("  print level         : " + printLevel);
        AppLogger.raw(String.format(Locale.ROOT, "  viewer fits         : Minuit2 / %s, strategy %d, tolerance %s",
            RootFitter.Options.defaultAlgorithm(), RootFitter.Options.defaultStrategy(), RootFitter.Options.defaultTolerance()));
        AppLogger.raw("  references platform : " + Minuit2Tests.platform());
    }

    /** A function of the parameters written as a formula: the value, and its gradient by automatic differentiation. */
    private static FCNBase function(RootFormula f, double up, boolean gradient) {
        final double[] vars = new double[4];
        if (!gradient) {
            return new FCNBase() {
                @Override
                public double value(double[] p) {
                    return f.eval(vars, p);
                }

                @Override
                public double up() {
                    return up;
                }
            };
        }
        return new FCNGradientBase() {
            @Override
            public double value(double[] p) {
                return f.eval(vars, p);
            }

            @Override
            public double[] gradient(double[] p) {
                final double[] g = new double[p.length];
                f.evalGradient(vars, p, g);
                return g;
            }

            @Override
            public double up() {
                return up;
            }
        };
    }

    private static double[] list(String s) {
        final String[] w = s.trim().split("[,\\s]+");
        final double[] v = new double[w.length];
        for (int i = 0; i < w.length; i++) v[i] = Double.parseDouble(w[i]);
        return v;
    }

    private static void minimize(Args a) {
        if (a.plain.isEmpty()) {
            Handlers.usage(":minuit2 minimize \"<formula of [0], [1]...>\" [start values] [--err ...] [--limit i:lo:hi] [--fix i] "
                + "[--up u] [--algo a] [--strategy s] [--grad] [--minos] [--auto] [--multistart k]");
            return;
        }
        final RootFormula f = RootFormula.parse(a.plain.get(0));
        final int n = Math.max(f.parameters(), a.plain.size() - 1);
        if (n == 0) {
            AppLogger.error("The formula has no parameter: write them [0], [1]... or [name].");
            return;
        }
        final double up = a.opt("up", 1.0);
        final FCNBase fcn = function(f, up, a.has("grad"));
        final double[] err = a.has("err") ? list(a.opts.get("err")) : null;
        final MnUserParameters upar = new MnUserParameters();
        for (int i = 0; i < n; i++) {
            final double v = a.num(i + 1, 0.);
            final double e = err != null && i < err.length ? err[i] : v != 0 ? 0.1 * Math.abs(v) : 0.1;
            upar.add(f.parameterName(i), v, e);
        }
        if (a.has("limit")) {
            for (String l : a.opts.get("limit").split("\\s+")) {
                final String[] p = l.split(":");
                final int i = Integer.parseInt(p[0]);
                final String lo = p.length > 1 ? p[1] : "";
                final String hi = p.length > 2 ? p[2] : "";
                if (!lo.isEmpty() && !hi.isEmpty()) upar.setLimits(i, Double.parseDouble(lo), Double.parseDouble(hi));
                else if (!lo.isEmpty()) upar.setLowerLimit(i, Double.parseDouble(lo));
                else if (!hi.isEmpty()) upar.setUpperLimit(i, Double.parseDouble(hi));
            }
        }
        if (a.has("fix")) for (String i : a.opts.get("fix").split("[,\\s]+")) upar.fix(Integer.parseInt(i));
        final int strategy = (int) a.opt("strategy", 1);
        final double tol = a.opt("tolerance", 0.1);
        final MnUserParameterState st = new MnUserParameterState(upar);
        final long t0 = System.nanoTime();
        final COStream os = new COStream();
        if (a.has("auto") || a.has("multistart")) {
            final Autopilot.Report r = Autopilot.minimize(fcn, st, new Autopilot.Options(strategy, tol, 0,
                (int) a.opt("multistart", 0), a.has("minos"), false));
            lastFcn = fcn;
            lastMin = r.best();
            os.put("Minuit2 autopilot on ").put(f.text()).put(":");
            r.best().print(os);
            os.endl();
            for (MinosError me : r.minos()) me.print(os);
            AppLogger.raw(os.str());
            AppLogger.raw(r.text());
            AppLogger.result(String.format(Locale.ROOT, "%d calls in %.1f ms; the minimum is kept for hesse, minos, profile, "
                + "contour and scan.", r.totalCalls(), (System.nanoTime() - t0) / 1e6));
            lastText = os.str();
            return;
        }
        final String algo = a.opts.getOrDefault("algo", "migrad").toLowerCase(Locale.ROOT);
        final FunctionMinimum min = switch (algo) {
            case "simplex" -> new MnSimplex(fcn, st, new MnStrategy(strategy)).minimize(0, tol);
            case "minimize" -> new MnMinimize(fcn, st, new MnStrategy(strategy)).minimize(0, tol);
            case "scan" -> new MnScan(fcn, st, new MnStrategy(strategy)).minimize(0, tol);
            case "bfgs" -> {
                final ModularFunctionMinimizer m = VariableMetricMinimizer.bfgs();
                yield m.minimize(fcn, st, new MnStrategy(strategy), 0, tol);
            }
            default -> new MnMigrad(fcn, st, new MnStrategy(strategy)).minimize(0, tol);
        };
        lastFcn = fcn;
        lastMin = min;
        os.put("Minuit2 ").put(algo).put(" on ").put(f.text()).put(":");
        min.print(os);
        os.endl();
        final List<MinosError> minos = new ArrayList<>();
        if (a.has("minos") && min.isValid()) {
            final MnMinos mn = new MnMinos(fcn, min, new MnStrategy(strategy));
            for (int i = 0; i < n; i++) {
                if (min.userState().parameter(i).isFixed()) continue;
                final MinosError me = mn.minos(i);
                minos.add(me);
                me.print(os);
            }
        }
        AppLogger.raw(os.str());
        for (String d : Autopilot.diagnose(min, minos, List.of())) AppLogger.raw("  - " + d);
        final String done = String.format(Locale.ROOT, "%s: FCN = %.10g after %d calls (%.1f ms).", min.isValid() ? "Converged"
            : "NOT converged (try --auto)", min.fval(), min.nfcn(), (System.nanoTime() - t0) / 1e6);
        if (min.isValid()) AppLogger.success(done);
        else AppLogger.warn(done);
        lastText = os.str();
    }

    private static boolean needMinimum() {
        if (lastMin == null) {
            AppLogger.error("No minimum yet: run ':minuit2 minimize' first.");
            return false;
        }
        return true;
    }

    private static void hesse(Args a) {
        if (!needMinimum()) return;
        new MnHesse((int) a.opt("strategy", 1)).apply(lastFcn, lastMin);
        final COStream os = new COStream();
        os.put("After Hesse:");
        lastMin.print(os);
        AppLogger.raw(os.str());
    }

    private static void minos(Args a) {
        if (!needMinimum()) return;
        if (!lastMin.isValid()) {
            AppLogger.error("The last minimum is not valid: Minos needs a valid minimum.");
            return;
        }
        if (a.has("up")) lastFcn.setErrorDef(a.opt("up", 1));
        if (a.has("up")) lastMin.setErrorDef(a.opt("up", 1));
        final MnMinos mn = new MnMinos(lastFcn, lastMin);
        final COStream os = new COStream();
        final int nall = lastMin.userState().minuitParameters().size();
        for (int i = 0; i < nall; i++) {
            if (!a.plain.isEmpty() && i != (int) a.num(0, -1)) continue;
            if (lastMin.userState().parameter(i).isFixed() || lastMin.userState().parameter(i).isConst()) continue;
            mn.minos(i).print(os);
        }
        AppLogger.raw(os.str());
    }

    private static int parameter(Args a, int k) {
        final String w = a.word(k, "0");
        try {
            return Integer.parseInt(w);
        } catch (NumberFormatException e) {
            return lastMin.userState().index(w);
        }
    }

    private static void profile(Args a) {
        if (!needMinimum()) return;
        final int par = parameter(a, 0);
        final Autopilot.Profile p = Autopilot.profile(lastFcn, lastMin, par, (int) a.opt("points", 21), a.opt("sigma", 3));
        AppLogger.raw(p.text(lastFcn.up()));
        final List<MnPrint.Point> pts = new ArrayList<>();
        for (int i = 0; i < p.x().length; i++) pts.add(new MnPrint.Point(p.x()[i], p.delta()[i]));
        AppLogger.raw(new MnPlot().render(pts));
    }

    private static void contour(Args a) {
        if (!needMinimum()) return;
        if (a.plain.size() < 2) {
            Handlers.usage(":minuit2 contour <i> <j> [--points n] [--up u]");
            return;
        }
        if (a.has("up")) {
            lastFcn.setErrorDef(a.opt("up", 1));
            lastMin.setErrorDef(a.opt("up", 1));
        }
        final ContoursError ce = new MnContours(lastFcn, lastMin).contour(parameter(a, 0), parameter(a, 1), (int) a.opt("points", 20));
        AppLogger.raw(ce.toString());
    }

    private static void scan(Args a) {
        if (!needMinimum()) return;
        final int par = parameter(a, 0);
        final MnParameterScan s = new MnParameterScan(lastFcn, lastMin.userParameters());
        final List<MnPrint.Point> pts = s.scan(par, (int) a.opt("steps", 41), a.opt("low", 0), a.opt("high", 0));
        final StringBuilder t = new StringBuilder();
        for (MnPrint.Point p : pts) t.append(String.format(Locale.ROOT, "  %14.7g  %14.7g%n", p.x(), p.y()));
        AppLogger.raw(t.toString());
        AppLogger.raw(new MnPlot().render(pts.subList(1, pts.size())));
        if (s.fval() < lastMin.fval()) {
            AppLogger.warn(String.format(Locale.ROOT, "The scan found a lower value (%.10g at %.7g): the minimum was not global.",
                s.fval(), s.parameters().value(par)));
        }
    }

    private static void defaults(Args a) {
        RootFitter.Options.setDefaults(a.has("algo") ? a.opts.get("algo") : null, (int) a.opt("strategy", -1),
            a.opt("tolerance", -1));
        AppLogger.result(String.format(Locale.ROOT, "The viewer's fits use Minuit2 / %s, strategy %d, tolerance %s.",
            RootFitter.Options.defaultAlgorithm(), RootFitter.Options.defaultStrategy(), RootFitter.Options.defaultTolerance()));
    }

    private static void longDouble(Args a) {
        final String w = a.word(0, "").toLowerCase(Locale.ROOT);
        switch (w) {
            case "x87" -> MnParameterTransformation.setLongDouble(MnParameterTransformation.LongDoubleKind.X87);
            case "double" -> MnParameterTransformation.setLongDouble(MnParameterTransformation.LongDoubleKind.DOUBLE);
            case "auto" -> MnParameterTransformation.setLongDouble(NativeMath.longDoubleIsDouble()
                ? MnParameterTransformation.LongDoubleKind.DOUBLE : MnParameterTransformation.LongDoubleKind.X87);
            default -> {
            }
        }
        AppLogger.result("The parameter transformations compute in long double = " + MnParameterTransformation.longDouble());
    }

    private static void run(Args a) {
        final String name = a.word(0, "");
        for (Programs.Program p : Programs.all()) {
            if (!p.name().equalsIgnoreCase(name)) continue;
            final Minuit2Tests.References refs;
            try {
                refs = Minuit2Tests.References.packaged(Minuit2Tests.platform());
            } catch (IOException e) {
                AppLogger.error("The reference data cannot be read: " + e.getMessage());
                return;
            }
            final Minuit2Tests.Run r = Minuit2Tests.execute(p, refs);
            AppLogger.raw(r.stdout());
            if (!r.stderr().isEmpty()) AppLogger.warn(r.stderr());
            AppLogger.result(String.format(Locale.ROOT, "%s: exit %d, %.0f ms", p.name(), r.exit(), r.millis()));
            return;
        }
        final List<String> names = new ArrayList<>();
        for (Programs.Program p : Programs.all()) names.add(p.name());
        AppLogger.error("No program '" + name + "'. They are: " + String.join(", ", names));
    }

    private static void validate(Args a) throws IOException {
        final Minuit2Tests.References refs = Minuit2Tests.References.packaged(Minuit2Tests.platform());
        if (refs == null) {
            AppLogger.error("No C++ outputs for " + Minuit2Tests.platform() + " in the jar (refdata/minuit2-"
                + Minuit2Tests.platform() + ".zip).");
            return;
        }
        AppLogger.info("ROOT's Minuit2 test programs and the bit probe, ported, against the C++ (" + refs.platform() + "); "
            + NativeMath.describe() + ", long double " + MnParameterTransformation.longDouble() + ":");
        int ok = 0;
        int n = 0;
        final long t0 = System.nanoTime();
        for (Minuit2Tests.Outcome o : Minuit2Tests.compareAll(a.word(0, ""), refs)) {
            n++;
            if (o.passed()) ok++;
            for (String l : Minuit2Tests.line(o).split("\n")) AppLogger.raw("  " + l);
        }
        final String summary = String.format(Locale.ROOT, "%d of %d agree with the C++, character for character "
            + "(the probe: every number of every iteration, as its 64 bits), in %.1f s.", ok, n, (System.nanoTime() - t0) / 1e9);
        if (ok == n && n > 0) AppLogger.success(summary);
        else AppLogger.warn(summary);
    }

    private static String cap(String algo) {
        final String a = algo.toLowerCase(Locale.ROOT);
        return switch (a) {
            case "bfgs" -> "BFGS";
            case "minimize" -> "Minimize";
            default -> Character.toUpperCase(a.charAt(0)) + a.substring(1);
        };
    }

    /** Declares sphere_minuit2.hpp in ROOT's interpreter; null (and said why) when ROOT is not there. */
    private static String header(CommandExecutionContext c) {
        try {
            final String h = com.sphere.core.bridge.Bridge.libraries().resolve("sphere_minuit2.hpp").toString().replace('\\', '/');
            final String r = Handlers.clingAnswer(c, "gInterpreter->Declare(\"#include \\\"" + h + "\\\"\")");
            return r == null ? null : h;
        } catch (IOException e) {
            AppLogger.error("The bridge header sphere_minuit2.hpp is not available: " + e.getMessage());
            return null;
        }
    }

    /** A 64-bit hex word as the double it holds. */
    private static double bits(String hex) {
        return Double.longBitsToDouble(Long.parseUnsignedLong(hex, 16));
    }

    private static void crosscheck(String input, CommandExecutionContext c) {
        final Args a = new Args(Handlers.args(input, ":root minuit2 crosscheck"));
        if (a.plain.size() < 2) {
            Handlers.usage(":root minuit2 crosscheck \"<formula of [0], [1]...>\" <start values> [--algo a] [--strategy s] "
                + "[--tolerance t] [--up u]");
            return;
        }
        final String expr = a.plain.get(0);
        final RootFormula f = RootFormula.parse(expr);
        final int n = a.plain.size() - 1;
        final double[] start = new double[n];
        for (int i = 0; i < n; i++) start[i] = a.num(i + 1, 0);
        final String algo = a.opts.getOrDefault("algo", "migrad");
        final int strategy = (int) a.opt("strategy", 1);
        final double tol = a.opt("tolerance", 0.01);
        final double up = a.opt("up", 1);
        // Java
        final double[] vars = new double[4];
        final com.sphere.core.minuit2.Minuit2Minimizer m = new com.sphere.core.minuit2.Minuit2Minimizer(algo);
        m.setFunction(n, p -> f.eval(vars, p));
        m.setStrategy(strategy);
        m.setTolerance(tol);
        m.setErrorDef(up);
        for (int i = 0; i < n; i++) m.setVariable(i, "p" + i, start[i], start[i] != 0 ? 0.1 * Math.abs(start[i]) : 0.1);
        final boolean ok = m.minimize();
        final double[] jx = m.x();
        final double[] je = m.errors();
        // C++
        if (header(c) == null) {
            AppLogger.warn("ROOT is not running: only the Java side ran (FCN = " + m.minValue() + ").");
            return;
        }
        final StringBuilder v = new StringBuilder("{");
        for (int i = 0; i < n; i++) v.append(i > 0 ? "," : "").append(Double.toString(start[i]));
        v.append('}');
        final String answer = Handlers.clingAnswer(c, "sphere::minuit2::minimize(\"" + expr.replace("\"", "\\\"") + "\", "
            + v + ", \"" + algo + "\", " + strategy + ", " + tol + ", " + up + ")");
        if (answer == null) return;
        final int q0 = answer.indexOf('"');
        final int q1 = answer.lastIndexOf('"');
        if (q0 < 0 || q1 <= q0) {
            AppLogger.error("ROOT answered: " + answer);
            return;
        }
        final String[] w = answer.substring(q0 + 1, q1).trim().split("\\s+");
        if (w.length < 5 + 2 * n) {
            AppLogger.error("ROOT answered: " + answer);
            return;
        }
        final boolean cok = w[0].equals("1");
        final double cf = bits(w[2]);
        final int ccalls = Integer.parseInt(w[4]);
        boolean same = cok == ok && cf == m.minValue() && ccalls == m.nCalls();
        final StringBuilder t = new StringBuilder();
        t.append(String.format(Locale.ROOT, "  %-10s %-24s %-24s %s%n", "", "ROOT (C++)", "Sphere (Java)", "difference"));
        t.append(String.format(Locale.ROOT, "  %-10s %-24s %-24s%n", "valid", cok, ok));
        t.append(String.format(Locale.ROOT, "  %-10s %-24d %-24d%n", "calls", ccalls, m.nCalls()));
        t.append(String.format(Locale.ROOT, "  %-10s %-24.17g %-24.17g %.3g%n", "FCN", cf, m.minValue(), m.minValue() - cf));
        for (int i = 0; i < n; i++) {
            final double cx = bits(w[5 + i]);
            final double ce = bits(w[5 + n + i]);
            same &= cx == jx[i] && ce == je[i];
            t.append(String.format(Locale.ROOT, "  %-10s %-24.17g %-24.17g %.3g sigma%n", f.parameterName(i), cx, jx[i],
                ce > 0 ? (jx[i] - cx) / ce : 0));
            t.append(String.format(Locale.ROOT, "  %-10s %-24.17g %-24.17g%n", "  error", ce, je[i]));
        }
        AppLogger.raw(t.toString());
        if (same) {
            AppLogger.success("ROOT's Minuit2 and Sphere's give the same bits: every value, every error, the same calls.");
        } else {
            AppLogger.warn("The results differ in their last bits: the formula itself is evaluated by TFormula in ROOT and by "
                + "Sphere's formula reader in Java, which may round a function differently; Minuit2 is the same on both sides "
                + "(':minuit2 validate' shows it bit for bit on the same function).");
        }
    }

    /** The rows ':minuit2 diag' adds to the engine's. */
    static void diagRows() {
        AppLogger.raw(String.format(Locale.ROOT, "  %-12s %s", "math", NativeMath.describe()));
        AppLogger.raw(String.format(Locale.ROOT, "  %-12s %s", "long double", MnParameterTransformation.longDouble()));
        AppLogger.raw(String.format(Locale.ROOT, "  %-12s %s", "references", Minuit2Tests.platform()
            + " (':minuit2 validate' compares bit for bit)"));
        AppLogger.raw(String.format(Locale.ROOT, "  %-12s Minuit2 / %s, strategy %d, tolerance %s", "fits",
            RootFitter.Options.defaultAlgorithm(), RootFitter.Options.defaultStrategy(), RootFitter.Options.defaultTolerance()));
        AppLogger.raw(String.format(Locale.ROOT, "  %-12s %s", "last", lastMin == null ? "no minimization yet"
            : String.format(Locale.ROOT, "FCN = %.10g, %s, %d calls", lastMin.fval(), lastMin.isValid() ? "valid" : "not valid",
                lastMin.nfcn())));
    }

    /** The text of the last minimization, for the other engines. */
    public static String lastText() {
        return lastText;
    }
}
