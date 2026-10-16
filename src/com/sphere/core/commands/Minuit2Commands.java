package com.sphere.core.commands;

import com.sphere.components.rootview.RootFitter;
import com.sphere.components.rootview.RootFormula;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.LongDouble;
import com.sphere.core.hepmc3.cxx.NativeMath;
import com.sphere.core.hepmc3.cxx.X87Abi;
import com.sphere.core.bridge.Bridge;
import com.sphere.core.bridge.Spx;
import com.sphere.core.bridge.SpxExport;
import com.sphere.core.minuit2.Autopilot;
import com.sphere.core.minuit2.ChiSquare;
import com.sphere.core.minuit2.DataFit;
import com.sphere.core.minuit2.MnUserCovariance;
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
import com.sphere.core.minuit2.NoiseEstimator;
import com.sphere.core.minuit2.SmartFCN;
import com.sphere.core.minuit2.VariableMetricMinimizer;
import com.sphere.core.minuit2.validation.AutopilotTests;
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
    private static SmartFCN lastFcn;
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
            + "[--tolerance t] [--grad] [--minos] [--cl 0.95|2] [--auto] [--iterate n] [--multistart k]", Minuit2Commands::minimize);
        reg("fit", "Fit a histogram any engine published (C++/ROOT macro, Python, Julia, Fortran: spx::publish) with the "
            + "autopilot, judge the fit (p-value, pulls, runs test) and publish it back for every engine. Usage: :minuit2 fit "
            + "<table> \"<formula of x and [0], [1]...>\" [start values] [--cost chi2|poisson] [--range a:b] [--minos] [--cl p]",
            Minuit2Commands::fit);
        reg("hesse", "Hesse on the last minimum. Usage: :minuit2 hesse [--strategy s]", Minuit2Commands::hesse);
        reg("minos", "Minos errors of the last minimum, at a confidence level (a probability, or a number of sigmas). Usage: "
            + ":minuit2 minos [parameter] [--cl 0.95|2] [--up u]", Minuit2Commands::minos);
        reg("profile", "The profile of a parameter (the others minimized at each point, following the valley) and where it "
            + "crosses the level. Usage: :minuit2 profile <parameter> [--points n] [--sigma k] [--cl 0.95|2]", Minuit2Commands::profile);
        reg("contour", "The contour of two parameters at FCNmin + up, or at a confidence level of the pair. Usage: :minuit2 contour "
            + "<i> <j> [--points n] [--cl 0.68|1] [--up u]", Minuit2Commands::contour);
        reg("noise", "The noise of the function at the last minimum (More-Wild) and the precision Minuit should be given",
            Minuit2Commands::noise);
        reg("selftest", "The autopilot on a gallery of difficult functions (noise, NaN, saddle, several minima, degeneracy), "
            + "and its tools checked. Usage: :minuit2 selftest", a -> {
                final List<AutopilotTests.Outcome> outs = AutopilotTests.runAll();
                int ok = 0;
                for (AutopilotTests.Outcome o : outs) {
                    if (o.passed()) ok++;
                    AppLogger.raw(String.format(Locale.ROOT, "  %s %-30s %s", o.passed() ? "PASS" : "FAIL", o.name(), o.detail()));
                }
                final String s = ok + " of " + outs.size() + " autopilot checks pass.";
                if (ok == outs.size()) AppLogger.success(s);
                else AppLogger.warn(s);
            });
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
        CommandDefinitions.register(":root minuit2 autopilot", "Sphere's autopilot on ROOT's own Minuit2 (sphere_autopilot.hpp "
            + "in ROOT's interpreter), beside the Java one. Usage: :root minuit2 autopilot \"<formula of [0], [1]...>\" <start "
            + "values> [--multistart k] [--minos] [--cl p] [--strategy s] [--tolerance t] [--up u]", Minuit2Commands::rootAutopilot);
        reg("x87", "The Sphere x87 ABI (long double on the x87 unit, sphere_x87.S): who computes, and the software checked "
            + "against the hardware bit for bit. Usage: :minuit2 x87 [check [n]|hardware|software]", a -> {
                final String w = a.word(0, "").toLowerCase(Locale.ROOT);
                if (w.equals("hardware") || w.equals("x87")) LongDouble.setBackend(LongDouble.Backend.X87);
                if (w.equals("software")) LongDouble.setBackend(LongDouble.Backend.SOFTWARE);
                AppLogger.raw("  ABI        : " + X87Abi.describe());
                AppLogger.raw("  long double: computed by " + (LongDouble.backend() == LongDouble.Backend.X87
                    ? "the x87 unit (sphere_x87.S)" : "Sphere's software (any processor)"));
                if (w.equals("check")) {
                    if (!X87Abi.available()) {
                        AppLogger.warn("No x87 unit here: the software long double serves, as checked on x86-64.");
                        return;
                    }
                    final X87Abi.Check ch = X87Abi.check((int) a.num(1, 20000), System.nanoTime());
                    if (ch.passed()) AppLogger.success(ch.text());
                    else AppLogger.warn(ch.text());
                }
            });
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
        AppLogger.raw("             --algo migrad|simplex|minimize|scan|bfgs, --strategy 0..3, --cl 0.95 or 2 (sigmas) for Minos");
        AppLogger.raw("  autopilot  --auto: the remedy of each failure (call limit, NaN -> domain limits, noise -> precision,");
        AppLogger.raw("             saddle -> step down), --iterate n attempts, --multistart k (MLSL starts, parallel if on)");
        AppLogger.raw("  then       :minuit2 hesse | minos [i] [--cl] | profile <i> [--cl] | contour <i> <j> [--cl] | scan <i> | noise");
        AppLogger.raw("  engines    :minuit2 fit <table> \"[0]*exp(-x/[1])\": a histogram any engine published (spx::publish), fitted");
        AppLogger.raw("             by the autopilot and published back as <table>_fit.spx; in C++ and ROOT macros,");
        AppLogger.raw("             #include \"sphere_autopilot.hpp\" gives ROOT's own Minuit2 the autopilot (:root minuit2 autopilot)");
        AppLogger.raw("  fits       the ROOT viewer's Fit (TH1::Fit, Fit Panel) uses Minuit2; :minuit2 default --algo fumili --strategy 2");
        AppLogger.raw("             fit options as ROOT: E Minos, G gradient (automatic differentiation of the formula), M autopilot");
        AppLogger.raw("  engine     :minuit2 info | longdouble x87|double | parallel on | print 1");
        AppLogger.raw("  trust      :minuit2 validate (ROOT's test programs and a bit probe against the C++) | run DemoGaussSim");
        AppLogger.raw("             :minuit2 selftest (the autopilot on noisy, undefined, saddle, multimodal, degenerate functions)");
        AppLogger.raw("  references Citations > Citation Minuit2, or :minuit2 bib <file>");
        AppLogger.raw("  TMinuit    ROOT's first minimizer, MINUIT's commands, side by side with Minuit2: :minuit help");
    }

    private static void info() {
        AppLogger.result(Minuit2Citations.versionLine());
        AppLogger.raw("  C library functions : " + NativeMath.describe());
        AppLogger.raw("  long double         : " + MnParameterTransformation.longDouble()
            + " (x87: GCC/Clang on x86-64; double: MSVC, Apple Silicon)");
        AppLogger.raw("  x87 arithmetic      : " + (LongDouble.backend() == LongDouble.Backend.X87
            ? "the x87 unit, through the Sphere x87 ABI (sphere_x87.S)" : "Sphere's software long double") + "; " + X87Abi.describe());
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

    /** The names of the formula's first n parameters. */
    private static List<String> parameterNames(RootFormula f, int n) {
        final List<String> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(f.parameterName(i));
        return l;
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
        final SmartFCN fcn = new SmartFCN(function(f, up, a.has("grad")));
        final double cl = a.opt("cl", 0);
        final double[] err = a.has("err") ? list(a.opts.get("err")) : null;
        final MnUserParameters upar = new MnUserParameters();
        final List<String> names = RootFitter.uniqueNames(parameterNames(f, n));
        for (int i = 0; i < n; i++) {
            final double v = a.num(i + 1, 0.);
            final double e = err != null && i < err.length ? err[i] : v != 0 ? 0.1 * Math.abs(v) : 0.1;
            upar.add(names.get(i), v, e);
        }
        constrain(a, upar);
        final int strategy = (int) a.opt("strategy", 1);
        final double tol = a.opt("tolerance", 0.1);
        final MnUserParameterState st = new MnUserParameterState(upar);
        final long t0 = System.nanoTime();
        final COStream os = new COStream();
        lastFcn = fcn;
        if (a.has("auto") || a.has("multistart") || a.has("iterate")) {
            final Autopilot.Report r = Autopilot.minimize(fcn, st, new Autopilot.Options(strategy, tol, 0,
                (int) a.opt("multistart", 0), a.has("minos"), false).withIterate((int) a.opt("iterate", 5)).withCl(cl));
            lastMin = r.best();
            os.put("Minuit2 autopilot on ").put(f.text()).put(":");
            r.best().print(os);
            os.endl();
            for (MinosError me : r.minos()) me.print(os);
            AppLogger.raw(os.str());
            AppLogger.raw(AppLogger.plusMinus(r.text()));
            AppLogger.result(String.format(Locale.ROOT, "%d calls (%d computed) in %.1f ms; the minimum is kept for hesse, minos, "
                + "profile, contour and scan.", r.totalCalls(), r.stats().evaluations(), (System.nanoTime() - t0) / 1e6));
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
        lastMin = min;
        os.put("Minuit2 ").put(algo).put(" on ").put(f.text()).put(":");
        min.print(os);
        os.endl();
        final List<MinosError> minos = new ArrayList<>();
        if (a.has("minos") && min.isValid()) {
            atLevel(ChiSquare.upFactor(cl, 1), () -> {
                final MnMinos mn = new MnMinos(fcn, min, new MnStrategy(strategy));
                for (int i = 0; i < n; i++) {
                    if (min.userState().parameter(i).isFixed()) continue;
                    final MinosError me = mn.minos(i);
                    minos.add(me);
                    me.print(os);
                }
            });
        }
        AppLogger.raw(os.str());
        AppLogger.raw(AppLogger.plusMinus(Autopilot.summary(min, minos, cl)));
        for (String d : Autopilot.diagnose(min, minos, List.of())) AppLogger.raw("  - " + d);
        AppLogger.raw(Autopilot.judge(min, minos).text());
        final String done = String.format(Locale.ROOT, "%s: FCN = %.10g after %d calls (%.1f ms).", min.isValid() ? "Converged"
            : "NOT converged (try --auto)", min.fval(), min.nfcn(), (System.nanoTime() - t0) / 1e6);
        if (min.isValid()) AppLogger.success(done);
        else AppLogger.warn(done);
        lastText = os.str();
    }

    /** --limit i:lo:hi (either side may be empty) and --fix i,j on the parameters. */
    private static void constrain(Args a, MnUserParameters upar) {
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
    }

    /** A table of the bridge by its name: as given, in the bridge folder or in its outbox, with or without .spx. */
    private static Path table(String name) throws IOException {
        final List<Path> tries = new ArrayList<>();
        for (String n : List.of(name, name + ".spx")) {
            tries.add(Handlers.resolve(n).toPath());
            tries.add(Bridge.folder().resolve(n));
            tries.add(Bridge.outbox().resolve(n));
        }
        for (Path p : tries) if (Files.isRegularFile(p)) return p;
        throw new IOException("no table '" + name + "' (looked in the working folder, " + Bridge.folder() + " and its outbox)");
    }

    /**
     * Fits a histogram any engine published (spx::publish in C++ or a ROOT macro, Python, Julia,
     * Fortran, or Sphere itself) with the autopilot, says how well the model fits, and publishes
     * the fit back as a table every engine reads: the model in each bin, the pulls, the
     * parameters, errors, Minos errors, covariance, chi2, ndf and p-value.
     */
    private static void fit(Args a) throws IOException {
        if (a.plain.size() < 2) {
            Handlers.usage(":minuit2 fit <table> \"<formula of x and [0], [1]...>\" [start values] [--cost chi2|poisson] "
                + "[--range a:b] [--limit i:lo:hi] [--fix i] [--minos] [--cl 0.95] [--multistart k] [--strategy s]");
            return;
        }
        final Path file = table(a.plain.get(0));
        double[] edges;
        double[] values;
        double[] errPlus = null;
        double[] errMinus = null;
        final String title;
        final String xLabel;
        final String engine;
        try (Spx.Reader r = new Spx.Reader(file)) {
            if (!r.has("edges") || !r.has("values")) {
                AppLogger.error(file.getFileName() + " holds no histogram (edges and values).");
                return;
            }
            edges = r.numbers("edges");
            values = r.numbers("values");
            if (r.has("err_plus")) errPlus = r.numbers("err_plus");
            if (r.has("err_minus")) errMinus = r.numbers("err_minus");
            final Map<String, String> meta = r.meta();
            title = meta.getOrDefault("Title", r.title()).trim();
            xLabel = meta.getOrDefault("XLabel", "x");
            engine = meta.getOrDefault("Engine", "an engine");
        }
        if (a.has("range")) {
            final String[] w = a.opts.get("range").split(":");
            final double lo = w[0].isEmpty() ? Double.NEGATIVE_INFINITY : Double.parseDouble(w[0]);
            final double hi = w.length < 2 || w[1].isEmpty() ? Double.POSITIVE_INFINITY : Double.parseDouble(w[1]);
            int first = 0;
            int last = values.length - 1;
            while (first < values.length && 0.5 * (edges[first] + edges[first + 1]) < lo) first++;
            while (last >= first && 0.5 * (edges[last] + edges[last + 1]) > hi) last--;
            if (last < first) {
                AppLogger.error("No bin in the range " + a.opts.get("range") + ".");
                return;
            }
            edges = java.util.Arrays.copyOfRange(edges, first, last + 2);
            values = java.util.Arrays.copyOfRange(values, first, last + 1);
            if (errPlus != null) errPlus = java.util.Arrays.copyOfRange(errPlus, first, last + 1);
            if (errMinus != null) errMinus = java.util.Arrays.copyOfRange(errMinus, first, last + 1);
        }
        final RootFormula f = RootFormula.parse(a.plain.get(1));
        final int m = Math.max(1, f.parameters());
        final double[][] xs = new double[values.length][];
        for (int k = 0; k < values.length; k++) xs[k] = new double[] {0.5 * (edges[k] + edges[k + 1])};
        final double[] p0 = a.plain.size() - 2 >= m ? new double[m] : RootFitter.guess(f, xs, values);
        if (a.plain.size() - 2 >= m) for (int i = 0; i < m; i++) p0[i] = a.num(i + 2, 0);
        final MnUserParameters upar = new MnUserParameters();
        final List<String> names = RootFitter.uniqueNames(parameterNames(f, m));
        for (int i = 0; i < m; i++) upar.add(names.get(i), p0[i], p0[i] != 0 ? 0.1 * Math.abs(p0[i]) : 0.1);
        constrain(a, upar);
        final DataFit.Cost cost = switch (a.opts.getOrDefault("cost", "").toLowerCase(Locale.ROOT)) {
            case "chi2" -> DataFit.Cost.CHI2;
            case "poisson", "likelihood" -> DataFit.Cost.POISSON;
            default -> null;
        };
        final double cl = a.opt("cl", 0);
        final Autopilot.Options opt = new Autopilot.Options((int) a.opt("strategy", 1), a.opt("tolerance", 0.1), 0,
            (int) a.opt("multistart", 0), a.has("minos"), false).withIterate((int) a.opt("iterate", 5)).withCl(cl);
        final long t0 = System.nanoTime();
        final DataFit.Result res = DataFit.fit(f::eval, edges, values, errPlus, errMinus, new MnUserParameterState(upar),
            opt, cost);
        final Autopilot.Report r = res.report();
        lastFcn = res.fcn();
        lastMin = r.best();
        final COStream os = new COStream();
        os.put("Minuit2 autopilot, ").put(f.text()).put(" fitted to ").put(title).put(" (").put(engine).put(", ")
            .put(values.length).put(" bins, ").put(res.cost() == DataFit.Cost.POISSON ? "Poisson likelihood" : "chi-square")
            .put("):");
        r.best().print(os);
        os.endl();
        for (MinosError me : r.minos()) me.print(os);
        AppLogger.raw(os.str());
        AppLogger.raw(AppLogger.plusMinus(r.text()));
        AppLogger.raw(res.text());
        final Path out = publishFit(title, xLabel, f, edges, values, res, cl);
        AppLogger.result(String.format(Locale.ROOT, "%d calls in %.1f ms; the fit is published as %s for every engine "
            + "(spx::File / SPXFile / SphereSPX: f64(\"parameters\"), f64(\"errors\"), f64(\"covariance\")...).",
            r.totalCalls(), (System.nanoTime() - t0) / 1e6, out.getFileName()));
        lastText = os.str();
    }

    /** The fit as a table in the outbox: the model per bin (drawn), the data, pulls, parameters and their errors. */
    private static Path publishFit(String title, String xLabel, RootFormula f, double[] edges, double[] values,
                                   DataFit.Result res, double cl) throws IOException {
        final FunctionMinimum best = res.report().best();
        final MnUserParameterState st = best.userState();
        final int n = st.minuitParameters().size();
        final double[] par = st.params();
        final double[] err = st.errors();
        final double[] lo = new double[n];
        final double[] hi = new double[n];
        java.util.Arrays.fill(lo, Double.NaN);
        java.util.Arrays.fill(hi, Double.NaN);
        for (MinosError me : res.report().minos()) {
            if (!me.isValid()) continue;
            lo[me.parameter()] = me.lower();
            hi[me.parameter()] = me.upper();
        }
        final double[] cov = new double[n * n];
        if (st.hasCovariance()) {
            final MnUserCovariance c = st.covariance();
            for (int i = 0; i < st.variableParameters(); i++) {
                for (int j = 0; j < st.variableParameters(); j++) {
                    cov[st.extOfInt(i) * n + st.extOfInt(j)] = c.get(i, j);
                }
            }
        }
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < n; i++) names.add(st.name(i));
        final Map<String, String> meta = new LinkedHashMap<>();
        meta.put("Title", title + " fit");
        meta.put("XLabel", xLabel);
        meta.put("Kind", "histogram");
        meta.put("Engine", "Sphere Minuit2 autopilot");
        meta.put("Fit", f.text());
        meta.put("Parameters", String.join(",", names));
        meta.put("Cost", res.cost().name());
        meta.put("Level", ChiSquare.describe(cl));
        meta.put("Verdict", res.report().verdict().text());
        meta.put("Summary", Autopilot.summary(best, res.report().minos(), cl).replace('\n', ';'));
        final Spx.Writer w = new Spx.Writer(Spx.Kind.TABLE, title + " fit");
        w.meta(meta);
        w.f64("edges", edges);
        w.f64("values", res.fitted());
        w.f64("data", values);
        w.f64("pulls", res.pulls());
        w.f64("parameters", par);
        w.f64("errors", err);
        w.f64("minos_lower", lo);
        w.f64("minos_upper", hi);
        w.f64("covariance", cov);
        w.f64("fcn", new double[] {best.fval()});
        w.f64("chi2", new double[] {res.chi2()});
        w.f64("ndf", new double[] {res.ndf()});
        w.f64("pvalue", new double[] {res.pValue()});
        w.f64("valid", new double[] {best.isValid() ? 1 : 0});
        Files.createDirectories(Bridge.outbox());
        return w.write(Bridge.outbox().resolve(SpxExport.safeName(title) + "_fit.spx"));
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
        setUp(a);
        final double cl = a.opt("cl", 0);
        final COStream os = new COStream();
        final List<MinosError> all = new ArrayList<>();
        final int only = a.plain.isEmpty() ? -1 : parameter(a, 0);
        atLevel(ChiSquare.upFactor(cl, 1), () -> {
            final MnMinos mn = new MnMinos(lastFcn, lastMin);
            final int nall = lastMin.userState().minuitParameters().size();
            for (int i = 0; i < nall; i++) {
                if (only >= 0 && i != only) continue;
                if (lastMin.userState().parameter(i).isFixed() || lastMin.userState().parameter(i).isConst()) continue;
                final MinosError me = mn.minos(i);
                all.add(me);
                me.print(os);
            }
        });
        AppLogger.raw(os.str());
        AppLogger.raw(AppLogger.plusMinus("At " + ChiSquare.describe(cl) + ":\n" + Autopilot.summary(lastMin, all, cl)));
    }

    /** --up u: Up of the last function and minimum, from now on (a chi-square 1, a -log L 0.5). */
    private static void setUp(Args a) {
        if (!a.has("up")) return;
        final double up = a.opt("up", 1);
        lastFcn.setErrorDef(up);
        lastMin.setErrorDef(up);
    }

    /** Runs body with Up times factor (a confidence level) on the last function and minimum, then puts Up back. */
    private static void atLevel(double factor, Runnable body) {
        final double up = lastFcn.up();
        if (factor != 1) {
            lastFcn.setErrorDef(up * factor);
            lastMin.setErrorDef(up * factor);
        }
        try {
            body.run();
        } finally {
            if (factor != 1) {
                lastFcn.setErrorDef(up);
                lastMin.setErrorDef(up);
            }
        }
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
        final double cl = a.opt("cl", 0);
        final Autopilot.Profile p = Autopilot.profile(lastFcn, lastMin, par, (int) a.opt("points", 21),
            a.opt("sigma", Math.max(3, 1.5 * Math.sqrt(ChiSquare.upFactor(cl, 1)))), cl);
        AppLogger.raw(p.text(lastFcn.up()));
        final List<MnPrint.Point> pts = new ArrayList<>();
        for (int i = 0; i < p.x().length; i++) pts.add(new MnPrint.Point(p.x()[i], p.delta()[i]));
        AppLogger.raw(new MnPlot().render(pts));
        final double x0 = lastMin.userState().value(par);
        if (!Double.isNaN(p.lowCross()) && !Double.isNaN(p.highCross())) {
            AppLogger.result(String.format(Locale.ROOT, "Profile interval of %s at %s: %s", p.name(), ChiSquare.describe(cl),
                com.sphere.core.minuit2.PdgFormat.asymmetric(x0, p.lowCross() - x0, p.highCross() - x0)));
        }
        double dmin = 0;
        for (double d : p.delta()) dmin = Math.min(dmin, d);
        if (dmin < -1e-3 * lastFcn.up()) {
            AppLogger.warn(String.format(Locale.ROOT, "The profile goes %.4g below the minimum: the minimum was not global; "
                + "try ':minuit2 minimize ... --multistart 10'.", -dmin));
        }
    }

    private static void contour(Args a) {
        if (!needMinimum()) return;
        if (a.plain.size() < 2) {
            Handlers.usage(":minuit2 contour <i> <j> [--points n] [--cl 0.68|1] [--up u]");
            return;
        }
        setUp(a);
        final double factor = a.has("cl") ? ChiSquare.upFactor(a.opt("cl", 0), 2) : 1;
        if (a.has("cl")) {
            AppLogger.info(String.format(Locale.ROOT, "Contour at %s of the pair: FCNmin + %.5g Up.", ChiSquare.describe(a.opt("cl", 0)),
                factor));
        }
        atLevel(factor, () -> {
            final ContoursError ce = new MnContours(lastFcn, lastMin).contour(parameter(a, 0), parameter(a, 1),
                (int) a.opt("points", 20));
            AppLogger.raw(ce.toString());
        });
    }

    private static void noise(Args a) {
        if (!needMinimum()) return;
        final NoiseEstimator.Estimate e = NoiseEstimator.estimate(lastFcn, lastMin.userState());
        AppLogger.raw("  " + e.text() + " (" + e.calls() + " calls)");
        final double eps = e.precisionForMinuit(lastFcn.up());
        final double edmGoal = 0.002 * 0.1 * lastFcn.up();
        if (eps > 4 * new com.sphere.core.minuit2.MnMachinePrecision().eps()) {
            AppLogger.result(String.format(Locale.ROOT, "Give Minuit the precision %.2g (state.setPrecision, SET EPS): its "
                + "numerical derivatives then step above the noise.", eps));
        } else {
            AppLogger.result("Minuit's default precision suits this function.");
        }
        if (e.found() && e.sigma() > 0.25 * edmGoal) {
            AppLogger.warn(String.format(Locale.ROOT, "The noise is above the EDM Migrad asks at tolerance 0.1 (%.3g): use a "
                + "tolerance of at least %.3g, or --auto.", edmGoal, 4 * e.sigma() / (0.002 * lastFcn.up())));
        }
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
        final String algo = RootFitter.Options.defaultAlgorithm();
        AppLogger.result(String.format(Locale.ROOT, "The viewer's fits use %s, strategy %d, tolerance %s.",
            algo.equalsIgnoreCase("tminuit") ? "TMinuit (ROOT's \"Minuit\" type)" : "Minuit2 / " + algo,
            RootFitter.Options.defaultStrategy(), RootFitter.Options.defaultTolerance()));
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
        return header(c, "sphere_minuit2.hpp");
    }

    /** Declares a bridge header in ROOT's interpreter; null (and said why) when ROOT is not there. */
    private static String header(CommandExecutionContext c, String name) {
        try {
            final String h = com.sphere.core.bridge.Bridge.libraries().resolve(name).toString().replace('\\', '/');
            final String r = Handlers.clingAnswer(c, "gInterpreter->Declare(\"#include \\\"" + h + "\\\"\")");
            return r == null ? null : h;
        } catch (IOException e) {
            AppLogger.error("The bridge header " + name + " is not available: " + e.getMessage());
            return null;
        }
    }

    /** The text of a std::string Cling printed: between its first and last quotes, unescaped. */
    private static String clingString(String answer) {
        final int q0 = answer.indexOf('"');
        final int q1 = answer.lastIndexOf('"');
        if (q0 < 0 || q1 <= q0) return null;
        final String s = answer.substring(q0 + 1, q1);
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            final char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                final char n = s.charAt(++i);
                out.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    /**
     * Sphere's autopilot run by ROOT itself: sphere_autopilot.hpp declared in ROOT's interpreter
     * drives ROOT's own Minuit2 on the formula (TFormula), beside Sphere's Java autopilot on the
     * same formula (Sphere's formula reader), and the two results side by side.
     */
    private static void rootAutopilot(String input, CommandExecutionContext c) {
        final Args a = new Args(Handlers.args(input, ":root minuit2 autopilot"));
        if (a.plain.size() < 2) {
            Handlers.usage(":root minuit2 autopilot \"<formula of [0], [1]...>\" <start values> [--multistart k] [--minos] "
                + "[--cl p] [--strategy s] [--tolerance t] [--up u]");
            return;
        }
        final String expr = a.plain.get(0);
        final int n = a.plain.size() - 1;
        final double[] start = new double[n];
        for (int i = 0; i < n; i++) start[i] = a.num(i + 1, 0);
        final int strategy = (int) a.opt("strategy", 1);
        final double tol = a.opt("tolerance", 0.1);
        final double up = a.opt("up", 1);
        final int k = (int) a.opt("multistart", 0);
        final double cl = a.opt("cl", 0);
        // Java
        final RootFormula f = RootFormula.parse(expr);
        final SmartFCN fcn = new SmartFCN(function(f, up, false));
        final MnUserParameters upar = new MnUserParameters();
        for (int i = 0; i < n; i++) upar.add("p" + i, start[i], start[i] != 0 ? 0.1 * Math.abs(start[i]) : 0.1);
        final Autopilot.Report java = Autopilot.minimize(fcn, new MnUserParameterState(upar),
            new Autopilot.Options(strategy, tol, 0, k, a.has("minos"), false).withCl(cl));
        lastFcn = fcn;
        lastMin = java.best();
        AppLogger.info("Sphere's autopilot (Java Minuit2):");
        AppLogger.raw(AppLogger.plusMinus(Autopilot.summary(java.best(), java.minos(), cl) + "  " + java.verdict().text()));
        // C++
        if (header(c, "sphere_autopilot.hpp") == null) {
            AppLogger.warn("ROOT is not running: only the Java side ran.");
            return;
        }
        final StringBuilder v = new StringBuilder("{");
        for (int i = 0; i < n; i++) v.append(i > 0 ? "," : "").append(Double.toString(start[i]));
        v.append('}');
        final String answer = Handlers.clingAnswer(c, "sphere::autopilot::formula(\"" + expr.replace("\"", "\\\"") + "\", "
            + v + ", " + strategy + ", " + tol + ", " + k + ", " + a.has("minos") + ", " + cl + ", " + up + ")");
        if (answer == null) return;
        final String text = clingString(answer);
        if (text == null) {
            AppLogger.error("ROOT answered: " + answer);
            return;
        }
        AppLogger.info("The same autopilot on ROOT's own Minuit2 (C++, sphere_autopilot.hpp):");
        AppLogger.raw(AppLogger.plusMinus(text));
        AppLogger.result("Both sides make the same decisions on the same numbers; the formula is read by TFormula in ROOT and "
            + "by Sphere's reader in Java, so the last bits may differ (':root minuit2 crosscheck' compares one Migrad bit "
            + "for bit). In a macro: #include \"sphere_autopilot.hpp\", then sphere::autopilot::minimize(fcn, params) or "
            + "sphere::autopilot::fit(h, f).");
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
