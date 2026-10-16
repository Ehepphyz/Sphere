package com.sphere.core.minuit2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * What an experienced Minuit user does when a fit goes wrong, done by the
 * program, and said in words. Every step is a plain Minuit2 call whose
 * numbers are the C++'s; what is added is the choice of the next step from
 * the cause of the failure:
 * <ul>
 * <li>the call limit: Migrad again from where it stopped, with more calls;</li>
 * <li>values that are not finite: the limits of the function's domain,
 *     inferred from where it failed, and Migrad inside them;</li>
 * <li>an EDM above the maximum: the noise of the function measured
 *     (More and Wild), Minuit's precision set to it, the tolerance raised to
 *     it when the noise is above the EDM asked;</li>
 * <li>otherwise, as iminuit: strategy 2 and Simplex then Migrad from the
 *     last point, a few times while it improves; then Simplex from the start;</li>
 * <li>a covariance that Hesse had to force positive-definite: the
 *     curvature measured, and when a direction goes down (a saddle point,
 *     not a minimum) a step along it and Migrad again;</li>
 * <li>several minima: starts chosen as in multi-level single linkage
 *     (Rinnooy Kan and Timmer): quasi-random points, Migrad only from those
 *     with no lower point near them, on several threads when allowed, and a
 *     catalogue of the minima with how many starts reached each.</li>
 * </ul>
 * The function goes through a {@link SmartFCN}: no point is computed twice.
 * Minos runs at any confidence level, the profile follows its valley from the
 * minimum outwards and finds where it crosses the level, and the result ends
 * with a diagnosis and a verdict.
 */
public final class Autopilot {

    private Autopilot() {
    }

    /**
     * What to try: iterate is the number of Migrad attempts on a failure (iminuit's), cl the level
     * of Minos (0: 1 sigma); minos runs Minos for every parameter, autoMinos only for those whose
     * function the parabolicity test finds not parabolic (or bounded) within the interval.
     */
    public record Options(int strategy, double tolerance, int maxfcn, int multistart, boolean minos, boolean verbose, int iterate,
                          double cl, boolean parallel, boolean autoMinos) {
        public static final Options DEFAULT = new Options(1, 0.1, 0, 0, false, false);

        public Options(int strategy, double tolerance, int maxfcn, int multistart, boolean minos, boolean verbose) {
            this(strategy, tolerance, maxfcn, multistart, minos, verbose, 5, 0, GradientCalculator.isParallel(), true);
        }

        public Options withMultistart(int k) {
            return new Options(strategy, tolerance, maxfcn, k, minos, verbose, iterate, cl, parallel, autoMinos);
        }

        public Options withMinos(boolean on) {
            return new Options(strategy, tolerance, maxfcn, multistart, on, verbose, iterate, cl, parallel, autoMinos);
        }

        public Options withIterate(int n) {
            return new Options(strategy, tolerance, maxfcn, multistart, minos, verbose, Math.max(1, n), cl, parallel, autoMinos);
        }

        public Options withCl(double level) {
            return new Options(strategy, tolerance, maxfcn, multistart, minos, verbose, iterate, level, parallel, autoMinos);
        }

        public Options withParallel(boolean on) {
            return new Options(strategy, tolerance, maxfcn, multistart, minos, verbose, iterate, cl, on, autoMinos);
        }

        public Options withAutoMinos(boolean on) {
            return new Options(strategy, tolerance, maxfcn, multistart, minos, verbose, iterate, cl, parallel, on);
        }
    }

    /**
     * How parabolic the function is along the linearized profile of one parameter: the rise
     * at -k and +k errors (the others moved by their regression on it), in units of k^2 Up,
     * 1 for a parabola; atLimit when the interval reaches a limit.
     */
    public record Parabolicity(int par, String name, double minus, double plus, boolean atLimit) {
        /** The function barely rises across the interval: Hesse's error does not describe it, and Minos would search in vain. */
        public boolean flat() {
            return !atLimit && minus < 0.01 && plus < 0.01;
        }

        /** Not a parabola to 10%, or bounded: Hesse's error is not the interval (Minos gives it). */
        public boolean needsMinos() {
            return !flat() && (atLimit || !(Math.abs(minus - 1) <= 0.1 && Math.abs(plus - 1) <= 0.1));
        }
    }

    /**
     * The parabolicity test of every variable parameter of a minimum with a covariance, at the
     * level cl: 2 values of the function per parameter, where Minos costs a minimization per
     * point of its search. It tells which parameters need Minos, and which do not.
     */
    public static List<Parabolicity> parabolicity(FCNBase fcn, FunctionMinimum m, double cl) {
        final List<Parabolicity> out = new ArrayList<>();
        final MnUserParameterState st = m.userState();
        if (!m.hasValidParameters() || !st.hasCovariance() || st.covarianceStatus() < 1) return out;
        final MnUserCovariance c = st.covariance();
        final int n = st.variableParameters();
        final double[] x0 = st.params();
        final double up = fcn.up();
        final double k = Math.sqrt(ChiSquare.upFactor(cl, 1));
        for (int i = 0; i < n; i++) {
            final double sigma = Math.sqrt(c.get(i, i));
            if (!(sigma > 0) || !Double.isFinite(sigma)) continue;
            final double[] r = new double[2];
            boolean atLimit = false;
            for (int s = 0; s < 2; s++) {
                final double sign = s == 0 ? -1 : 1;
                final double[] x = x0.clone();
                for (int j = 0; j < n; j++) {
                    final int e = st.extOfInt(j);
                    x[e] += sign * k * c.get(j, i) / sigma;
                    final MinuitParameter p = st.parameter(e);
                    if (p.hasLowerLimit() && x[e] < p.lowerLimit() || p.hasUpperLimit() && x[e] > p.upperLimit()) atLimit = true;
                }
                r[s] = atLimit ? Double.NaN : (fcn.value(x) - m.fval()) / (k * k * up);
            }
            out.add(new Parabolicity(st.extOfInt(i), st.name(st.extOfInt(i)), r[0], r[1], atLimit));
        }
        return out;
    }

    /** One attempt. */
    public record Step(String what, FunctionMinimum minimum, int calls, String outcome) {
    }

    /** A minimum and the number of starts that ended in it. */
    public record Basin(FunctionMinimum minimum, int hits) {
    }

    /** How far the result can be trusted. */
    public enum Grade {
        RELIABLE("reliable"), CAUTION("usable with care"), UNRELIABLE("not reliable");

        public final String words;

        Grade(String words) {
            this.words = words;
        }
    }

    /** The grade and what decided it. */
    public record Verdict(Grade grade, List<String> reasons) {
        public String text() {
            if (reasons.isEmpty()) return "Verdict: " + grade.words + ".";
            return "Verdict: " + grade.words + " (" + String.join("; ", reasons) + ").";
        }
    }

    /** One thing found about a minimum: its text, and whether it calls for care or makes the result unreliable. */
    record Finding(Grade severity, String text, String reason) {
        static Finding info(String text) {
            return new Finding(Grade.RELIABLE, text, null);
        }
    }

    /**
     * The best minimum, the attempts, the minima found (the best first), Minos
     * errors when asked (at level cl), the diagnosis, the verdict, the calls
     * asked of the function and what it cost, its noise when measured.
     */
    public record Report(FunctionMinimum best, List<Step> steps, List<Basin> basins, List<MinosError> minos,
                         List<String> diagnosis, Verdict verdict, int totalCalls, SmartFCN.Stats stats,
                         NoiseEstimator.Estimate noise, double cl) {

        /** The minima other than the best. */
        public List<FunctionMinimum> otherMinima() {
            final List<FunctionMinimum> l = new ArrayList<>();
            for (Basin b : basins) if (b.minimum() != best) l.add(b.minimum());
            return l;
        }

        public String text() {
            final StringBuilder s = new StringBuilder();
            s.append("Steps:\n");
            for (Step st : steps) {
                s.append(String.format(Locale.ROOT, "  %-58s %6d calls  %s%n", st.what(), st.calls(), st.outcome()));
            }
            if (basins.size() > 1 || basins.size() == 1 && basins.get(0).hits() > 1) {
                int starts = 0;
                for (Basin b : basins) starts += b.hits();
                s.append(String.format(Locale.ROOT, "Minima found (%d starts):%n", starts));
                s.append(String.format(Locale.ROOT, "     %-12s %-7s %s%n", "FCN - best", "starts", "at"));
                for (Basin b : basins) {
                    s.append(String.format(Locale.ROOT, "  %s  %-12.6g %-7d %s%s%n", b.minimum() == best ? "*" : " ",
                        b.minimum().fval() - best.fval(), b.hits(), values(b.minimum().userState()),
                        b.minimum().isValid() ? "" : " (not valid)"));
                }
            }
            s.append("Function: ").append(stats.text()).append('\n');
            s.append("Result:\n").append(summary(best, minos, cl));
            s.append("Diagnosis:\n");
            for (String d : diagnosis) s.append("  - ").append(d).append('\n');
            s.append(verdict.text()).append('\n');
            return s.toString();
        }
    }

    /** The parameters as the PDG rounds them, with the Minos errors when there are. */
    public static String summary(FunctionMinimum min, List<MinosError> minos, double cl) {
        final StringBuilder s = new StringBuilder();
        final MnUserParameterState st = min.userState();
        for (int i = 0; i < st.minuitParameters().size(); i++) {
            final MinuitParameter p = st.parameter(i);
            s.append(String.format(Locale.ROOT, "  %-12s = ", p.name()));
            if (p.isFixed() || p.isConst()) {
                s.append(String.format(Locale.ROOT, "%.10g (fixed)%n", p.value()));
                continue;
            }
            MinosError me = null;
            for (MinosError m : minos) if (m.parameter() == i && m.isValid()) me = m;
            if (me != null) {
                s.append(PdgFormat.asymmetric(p.value(), me.lower(), me.upper())).append("  (Minos, ")
                    .append(ChiSquare.describe(cl)).append(")");
            } else {
                s.append(PdgFormat.format(p.value(), p.error()));
            }
            s.append('\n');
        }
        return s.toString();
    }

    static String values(MnUserParameterState st) {
        final StringBuilder s = new StringBuilder("(");
        for (int i = 0; i < st.minuitParameters().size(); i++) {
            if (i > 0) s.append(", ");
            s.append(st.name(i)).append('=').append(String.format(Locale.ROOT, "%.6g", st.value(i)));
        }
        return s.append(')').toString();
    }

    static String outcome(FunctionMinimum m) {
        if (m.isValid()) {
            return String.format(Locale.ROOT, "valid, FCN = %.10g, EDM = %.3g%s", m.fval(), m.edm(),
                m.hasAccurateCovar() ? "" : m.hasMadePosDefCovar() ? ", covariance made pos-def" : ", covariance approximate");
        }
        final List<String> why = new ArrayList<>();
        if (m.hasReachedCallLimit()) why.add("call limit reached");
        if (m.isAboveMaxEdm()) why.add("EDM above maximum");
        if (!m.state().isValid()) why.add("state invalid");
        if (m.hesseFailed()) why.add("Hesse failed");
        return String.format(Locale.ROOT, "NOT valid (%s), FCN = %.10g", String.join(", ", why), m.fval());
    }

    /** The value, a value that is not finite counting as infinite. */
    private static double fv(FunctionMinimum m) {
        return Double.isFinite(m.fval()) ? m.fval() : Double.POSITIVE_INFINITY;
    }

    /** The better of two minima: a valid one, then the lower value. */
    static FunctionMinimum better(FunctionMinimum a, FunctionMinimum b) {
        if (a == null || b == null) return a == null ? b : a;
        if (a.isValid() != b.isValid()) return a.isValid() ? a : b;
        return fv(b) < fv(a) ? b : a;
    }

    /** Whether two minima are apart by more than 3 errors in some parameter and in function value. */
    static boolean distinct(FunctionMinimum a, FunctionMinimum b) {
        if (Math.abs(a.fval() - b.fval()) < 1e-3 * a.up()) return false;
        return !sameBasin(a, b);
    }

    /** Whether two minima are at the same place: every parameter within 3 errors (the larger of the two). */
    static boolean sameBasin(FunctionMinimum a, FunctionMinimum b) {
        final MnUserParameterState sa = a.userState();
        final MnUserParameterState sb = b.userState();
        for (int i = 0; i < sa.minuitParameters().size(); i++) {
            if (sa.parameter(i).isFixed() || sa.parameter(i).isConst()) continue;
            double e = Math.max(sa.error(i), sb.error(i));
            if (!(e > 0) || !Double.isFinite(e)) e = 1e-4 * Math.max(1, Math.abs(sa.value(i)));
            if (Math.abs(sa.value(i) - sb.value(i)) > 3 * e) return false;
        }
        return true;
    }

    /** The radical inverse in a prime base: Halton's points, spread evenly whatever their number. */
    static double halton(int index, int base) {
        double f = 1;
        double r = 0;
        int i = index;
        while (i > 0) {
            f /= base;
            r += f * (i % base);
            i /= base;
        }
        return r;
    }

    private static final int[] PRIMES = {2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47, 53, 59, 61, 67, 71, 73, 79,
        83, 89, 97, 101, 103, 107, 109, 113};

    /** The box of the spread starts for parameter p: its limits (both), or its value +- 5 errors inside its limit. */
    static double[] box(MinuitParameter p) {
        if (p.hasLowerLimit() && p.hasUpperLimit()) return new double[] {p.lowerLimit(), p.upperLimit()};
        final double e = 5 * Math.max(p.error(), 1e-8);
        double lo = p.value() - e;
        double hi = p.value() + e;
        if (p.hasLowerLimit()) lo = Math.max(lo, p.lowerLimit());
        if (p.hasUpperLimit()) hi = Math.min(hi, p.upperLimit());
        return new double[] {lo, hi};
    }

    /**
     * Start number s of a spread: each variable parameter inside its limits
     * (both), or within its value +- 5 errors (one or no limit), at the
     * Halton point of index s.
     */
    static MnUserParameterState spread(MnUserParameterState start, int s) {
        final MnUserParameterState st = new MnUserParameterState(start);
        int d = 0;
        for (int i = 0; i < st.minuitParameters().size(); i++) {
            final MinuitParameter p = st.parameter(i);
            if (p.isFixed() || p.isConst()) continue;
            final double u = halton(s, PRIMES[d++ % PRIMES.length]);
            st.setValue(i, at(box(p), u));
        }
        return st;
    }

    /** The point u in [0, 1] of a box, kept a thousandth of its width from its edges. */
    private static double at(double[] box, double u) {
        final double margin = 1e-3 * (box[1] - box[0]);
        return box[0] + margin + u * (box[1] - box[0] - 2 * margin);
    }

    /* ---- the autopilot ------------------------------------------------------------- */

    /** Minimizes with escalation, then (as asked) multi-start, Minos and a diagnosis. */
    public static Report minimize(FCNBase userFcn, MnUserParameterState start, Options opt) {
        final SmartFCN fcn = SmartFCN.of(userFcn);
        final Run run = new Run(fcn, start, opt);
        final long r0 = fcn.stats().requests();

        FunctionMinimum best = run.migrad("Migrad, strategy " + opt.strategy(), start, opt.strategy(), run.maxfcn);
        if (best == null) throw new IllegalArgumentException("Migrad cannot start: " + run.steps.get(0).outcome());
        best = run.repair(best, start);
        best = run.polish(best);

        // spread starts: when asked, or when no single start worked
        final int k = opt.multistart() > 0 ? opt.multistart() : best.isValid() ? 0 : 8;
        List<Basin> basins = List.of(new Basin(best, 1));
        if (k > 0) {
            basins = run.multistart(best, start, k);
            final FunctionMinimum top = basins.get(0).minimum();
            if (top != best) {
                best = run.polish(top);
                final FunctionMinimum b = best;
                final List<Basin> l = new ArrayList<>(basins);
                l.set(0, new Basin(b, basins.get(0).hits()));
                basins = l;
            }
        }

        // where Hesse's error is not the interval: the parabolicity test, two values per parameter
        final List<Parabolicity> parab = new ArrayList<>();
        if (best.isValid() && (opt.minos() || opt.autoMinos())) {
            final long r0p = fcn.stats().requests();
            parab.addAll(parabolicity(fcn, best, opt.cl()));
            final long need = parab.stream().filter(Parabolicity::needsMinos).count();
            if (!parab.isEmpty()) run.steps.add(new Step("Parabolicity test at " + ChiSquare.describe(opt.cl()), best,
                (int) (fcn.stats().requests() - r0p), need + " of " + parab.size() + " parameters not parabolic"));
        }

        // Minos at the level asked: every parameter when asked, else those the test points at
        final List<Integer> which = new ArrayList<>();
        for (int i = 0; i < best.userState().minuitParameters().size(); i++) {
            final MinuitParameter p = best.userState().parameter(i);
            if (p.isFixed() || p.isConst()) continue;
            if (opt.minos()) which.add(i);
        }
        if (!opt.minos() && opt.autoMinos()) for (Parabolicity q : parab) if (q.needsMinos()) which.add(q.par());
        final List<MinosError> minos = new ArrayList<>();
        if (!which.isEmpty() && best.isValid()) {
            final double up0 = fcn.up();
            final double factor = ChiSquare.upFactor(opt.cl(), 1);
            final FunctionMinimum b = best;
            final List<String> names = new ArrayList<>();
            for (int i : which) names.add(b.userState().name(i));
            run.step("Minos at " + ChiSquare.describe(opt.cl()) + " for " + String.join(", ", names), () -> {
                try {
                    fcn.setErrorDef(up0 * factor);
                    if (factor != 1) b.setErrorDef(up0 * factor);
                    final MnMinos mn = new MnMinos(fcn, b, new MnStrategy(opt.strategy()));
                    for (int i : which) minos.add(mn.minos(i));
                } finally {
                    fcn.setErrorDef(up0);
                    if (factor != 1) b.setErrorDef(up0);
                }
                return b;
            });
        }

        // the noise near the minimum, when the repairs did not measure it: what it does to the result
        if (run.noise == null && best.hasValidParameters()) {
            final long r0n = fcn.stats().requests();
            run.noise = NoiseEstimator.estimate(fcn, best.userState());
            run.steps.add(new Step("Noise at the minimum (More-Wild)", best, (int) (fcn.stats().requests() - r0n),
                run.noise.found() ? String.format(Locale.ROOT, "sigma = %.3g", run.noise.sigma()) : "below detection"));
        }

        final List<FunctionMinimum> others = new ArrayList<>();
        for (Basin bs : basins) if (bs.minimum() != best && bs.minimum().isValid()) others.add(bs.minimum());
        final List<Finding> findings = findings(best, minos, others);
        findings.addAll(run.findings(best, basins));
        final List<String> bent = new ArrayList<>();
        for (Parabolicity q : parab) {
            if (!q.needsMinos()) continue;
            bent.add(q.atLimit() ? q.name() + " (its interval reaches a limit)"
                : String.format(Locale.ROOT, "%s (it rises %.2f and %.2f times a parabola's)", q.name(), q.minus(), q.plus()));
        }
        final List<String> flat = new ArrayList<>();
        for (Parabolicity q : parab) if (q.flat()) flat.add(q.name());
        if (!flat.isEmpty()) {
            findings.add(new Finding(Grade.CAUTION, "The function barely rises across the Hesse interval of " + String.join(", ", flat)
                + " (less than 1% of a parabola's rise): the error does not describe it, and the function may not depend on "
                + (flat.size() > 1 ? "them" : "it") + " there; Minos was not run, it would search in vain.",
                "a parameter the function barely depends on"));
        }
        if (!parab.isEmpty() && (flat.isEmpty() || !bent.isEmpty())) {
            findings.add(Finding.info(bent.isEmpty()
                ? "Every parameter is a parabola to 10% across its interval: Hesse's errors are the intervals"
                    + (opt.minos() ? "; Minos confirms them." : ", Minos is not needed.")
                : "Not a parabola across the interval: " + String.join(", ", bent) + (opt.minos() || opt.autoMinos()
                    ? "; Minos gives the interval." : ".")));
        }
        final List<String> diagnosis = new ArrayList<>();
        for (Finding f : findings) diagnosis.add(f.text());
        return new Report(best, run.steps, basins, minos, diagnosis, verdict(findings),
            (int) (fcn.stats().requests() - r0), fcn.stats(), run.noise, opt.cl());
    }

    /** One run of the autopilot: the function, the options, what it learned on the way. */
    private static final class Run {
        final SmartFCN fcn;
        final Options opt;
        final int maxfcn;
        final List<Step> steps = new ArrayList<>();
        /** Minuit's precision (0: its default) and the tolerance, as the noise made them. */
        double precision;
        double precisionAt;
        double tolerance;
        NoiseEstimator.Estimate noise;
        List<SmartFCN.DomainHint> limits = List.of();
        /** The errors Migrad gave before Hesse replaced them, per minimum. */
        final Map<FunctionMinimum, double[]> migradErrors = new IdentityHashMap<>();
        /** The saddle points left: the value there, the value of the minimum found below, the curvature. */
        final List<double[]> escapes = new ArrayList<>();
        double lastCurvature = Double.NaN;
        /** A direction along which the curvature check found the function flat, in words (null: none). */
        String flat;

        Run(SmartFCN fcn, MnUserParameterState start, Options opt) {
            this.fcn = fcn;
            this.opt = opt;
            this.start = start;
            final int npar = start.variableParameters();
            this.maxfcn = opt.maxfcn() > 0 ? opt.maxfcn() : 200 + 100 * npar + 5 * npar * npar;
            this.tolerance = opt.tolerance();
        }

        final MnUserParameterState start;

        double edmGoal() {
            return 0.002 * tolerance * fcn.up();
        }

        /** A step whose calls are the function values asked during it. */
        FunctionMinimum step(String what, Supplier<FunctionMinimum> body) {
            final long r0 = fcn.stats().requests();
            try {
                final FunctionMinimum m = body.get();
                steps.add(new Step(what, m, (int) (fcn.stats().requests() - r0), outcome(m)));
                return m;
            } catch (RuntimeException e) {
                // a step that cannot run (the state it was given is not usable) is a failed attempt, not the end
                steps.add(new Step(what, null, (int) (fcn.stats().requests() - r0), "failed: " + (e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage())));
                return null;
            }
        }

        /**
         * A copy of s with what was learned: Minuit's precision, the limits of
         * the domain (the value moved inside); a value or an error that is not
         * finite (left by a failed attempt) is put back to the start's.
         */
        MnUserParameterState prepared(MnUserParameterState s) {
            boolean sane = true;
            for (MinuitParameter p : s.minuitParameters()) {
                if (!p.isConst()) sane &= Double.isFinite(p.value()) && p.error() > 0 && Double.isFinite(p.error());
            }
            final MnUserParameterState st = sane ? new MnUserParameterState(s) : new MnUserParameterState(s.parameters());
            if (!sane) {
                for (int i = 0; i < st.minuitParameters().size(); i++) {
                    final MinuitParameter p = st.parameter(i);
                    if (p.isConst()) continue;
                    if (!Double.isFinite(p.value())) st.setValue(i, start.value(i));
                    if (!(p.error() > 0) || !Double.isFinite(p.error())) {
                        st.setError(i, start.error(i) > 0 ? start.error(i) : 0.1 * Math.max(1, Math.abs(p.value())));
                    }
                }
            }
            for (SmartFCN.DomainHint h : limits) {
                final MinuitParameter p = st.parameter(h.par());
                if (p.isFixed() || p.isConst()) continue;
                final double step = Math.max(p.error() > 0 && Double.isFinite(p.error()) ? p.error() : 0,
                    1e-8 * Math.max(1, Math.abs(h.bound())));
                if (h.lower()) {
                    if (p.hasUpperLimit()) st.setLimits(h.par(), h.bound(), p.upperLimit());
                    else st.setLowerLimit(h.par(), h.bound());
                    if (!(st.value(h.par()) > h.bound())) {
                        double v = h.bound() + step;
                        if (p.hasUpperLimit()) v = Math.min(v, 0.5 * (h.bound() + p.upperLimit()));
                        st.setValue(h.par(), v);
                    }
                } else {
                    if (p.hasLowerLimit()) st.setLimits(h.par(), p.lowerLimit(), h.bound());
                    else st.setUpperLimit(h.par(), h.bound());
                    if (!(st.value(h.par()) < h.bound())) {
                        double v = h.bound() - step;
                        if (p.hasLowerLimit()) v = Math.max(v, 0.5 * (h.bound() + p.lowerLimit()));
                        st.setValue(h.par(), v);
                    }
                }
            }
            if (precision > 0) st.setPrecision(precision);
            return st;
        }

        FunctionMinimum migrad(String what, MnUserParameterState from, int strategy, int calls) {
            return step(what, () -> new MnMigrad(fcn, prepared(from), new MnStrategy(strategy)).minimize(calls, tolerance));
        }

        FunctionMinimum simplex(String what, MnUserParameterState from, int strategy, int calls) {
            return step(what, () -> new MnSimplex(fcn, prepared(from), new MnStrategy(strategy)).minimize(calls, tolerance));
        }

        /** The remedy for each cause of failure, until the minimum is valid, the attempts are spent or nothing improves. */
        FunctionMinimum repair(FunctionMinimum m, MnUserParameterState start) {
            boolean noiseTried = false;
            boolean domainTried = !limits.isEmpty();
            boolean toleranceRaised = false;
            int recalibrations = 0;
            int stalled = 0;
            for (int attempt = 1; !m.isValid() && attempt < opt.iterate() && stalled < 2; attempt++) {
                final FunctionMinimum n;
                final List<SmartFCN.DomainHint> hints = domainTried ? List.of() : fcn.domainHints(m.userState());
                if (m.hasReachedCallLimit()) {
                    n = migrad("Migrad again from where it stopped, 4x the calls", m.userState(), opt.strategy(), 4 * maxfcn);
                } else if (!hints.isEmpty()) {
                    domainTried = true;
                    limits = hints;
                    final List<String> t = new ArrayList<>();
                    for (SmartFCN.DomainHint h : hints) t.add(h.text());
                    n = migrad("Migrad, strategy 2, inside the domain " + String.join(", ", t), m.userState(), 2, maxfcn);
                } else if (m.isAboveMaxEdm() && !noiseTried) {
                    noiseTried = true;
                    final MnUserParameterState at = m.userState();
                    final long r0 = fcn.stats().requests();
                    noise = NoiseEstimator.estimate(fcn, at);
                    steps.add(new Step("Noise of the function (More-Wild)", m, (int) (fcn.stats().requests() - r0),
                        noise.found() ? String.format(Locale.ROOT, "sigma = %.3g", noise.sigma()) : "below detection"));
                    // Minuit sizes its steps on |F| where it stands: at the end, about the lowest value seen
                    precisionAt = Math.min(fcn.lowest(), m.fval());
                    final double eps = noise.precisionForMinuit(fcn.up(), precisionAt);
                    if (eps > 4 * new MnMachinePrecision().eps()) {
                        precision = eps;
                        n = migrad(String.format(Locale.ROOT, "Migrad, strategy 2, precision %.2g (the noise)", eps), at, 2, maxfcn);
                    } else {
                        n = simplexThenMigrad(at);
                    }
                } else if (m.isAboveMaxEdm() && precision > 0 && recalibrations < 2
                    && Math.abs(m.fval()) + fcn.up() < 0.5 * (Math.abs(precisionAt) + fcn.up())) {
                    // the minimum is far below where the precision was set: set it again for the value there
                    recalibrations++;
                    precisionAt = m.fval();
                    precision = Math.max(precision, noise.precisionForMinuit(fcn.up(), precisionAt));
                    n = migrad(String.format(Locale.ROOT, "Migrad, strategy 2, precision %.2g (the noise at FCN %.3g)", precision,
                        precisionAt), m.userState(), 2, maxfcn);
                } else if (m.isAboveMaxEdm() && noise != null && noise.found() && !toleranceRaised
                    && noise.sigma() > 0.25 * edmGoal()) {
                    toleranceRaised = true;
                    tolerance = Math.min(1e3 * opt.tolerance(), Math.max(tolerance, 4 * noise.sigma() / (0.002 * fcn.up())));
                    n = migrad(String.format(Locale.ROOT, "Migrad, strategy 2, tolerance %.3g (above the noise)", tolerance),
                        m.userState(), 2, maxfcn);
                } else {
                    n = simplexThenMigrad(m.userState());
                }
                final FunctionMinimum b = better(m, n);
                stalled = b == n && fv(n) < fv(m) - 1e-6 * fcn.up() || b.isValid() ? 0 : stalled + 1;
                m = b;
            }
            if (!m.isValid() && opt.iterate() > 1) {
                final FunctionMinimum sx = simplex("Simplex from the start, to leave a bad region", start, 2, 10 * maxfcn);
                if (sx != null) m = better(m, migrad("Migrad, strategy 2, from Simplex's point", sx.userState(), 2, 4 * maxfcn));
            }
            return m;
        }

        /** Simplex then Migrad, strategy 2, from there (iminuit's remedy for a pathological case). */
        FunctionMinimum simplexThenMigrad(MnUserParameterState from) {
            final FunctionMinimum sx = simplex("Simplex, strategy 2, from there", from, 2, maxfcn);
            return sx == null ? null : migrad("Migrad, strategy 2, from Simplex's point", sx.userState(), 2, maxfcn);
        }

        /** An accurate covariance (Hesse at strategy 2, then 3), and no saddle point taken for a minimum. */
        FunctionMinimum polish(FunctionMinimum m) {
            m = hesse(m);
            for (int e = 0; e < 3 && m.hasValidParameters(); e++) {
                if (m.hasAccurateCovar() && m.hasPosDefCovar() && !m.hasMadePosDefCovar()) break;
                final MnUserParameterState below = saddle(m);
                if (below == null) break;
                FunctionMinimum n = migrad("Migrad, strategy 2, from below the saddle point", below, 2, maxfcn);
                if (n == null) break;
                n = repair(n, below);
                n = hesse(n);
                if (!n.hasValidParameters() || !(fv(n) < fv(m) - 1e-6 * fcn.up())) break;
                escapes.add(new double[] {m.fval(), n.fval(), lastCurvature});
                m = n;
            }
            return m;
        }

        FunctionMinimum hesse(FunctionMinimum m) {
            if (!m.hasValidParameters() || m.hasAccurateCovar()) return m;
            if (m.hasCovariance()) migradErrors.put(m, m.userState().errors());
            for (int s = 2; s <= 3 && !m.hasAccurateCovar(); s++) {
                final int stra = s;
                step("Hesse, strategy " + s + (s == 3 ? " (central differences)" : ""), () -> {
                    new MnHesse(stra).apply(fcn, m, maxfcn);
                    return m;
                });
            }
            return m;
        }

        /**
         * The curvature at m in units of the errors, by finite differences of
         * the function (2n^2 + 1 values, the parameters away from their
         * limits); when a direction goes down, the lower of the two points one
         * Up below along it, else null.
         */
        MnUserParameterState saddle(FunctionMinimum m) {
            final MnUserParameterState st = m.userState();
            final double[] x0 = st.params();
            final List<Integer> idx = new ArrayList<>();
            final List<Double> scale = new ArrayList<>();
            final double h = 0.1;
            for (int i = 0; i < x0.length; i++) {
                final MinuitParameter p = st.parameter(i);
                if (p.isFixed() || p.isConst()) continue;
                final double s = p.error() > 0 && Double.isFinite(p.error()) ? p.error() : 1e-3 * Math.max(1, Math.abs(x0[i]));
                if (p.hasLowerLimit() && x0[i] - 2 * h * s <= p.lowerLimit()) continue;
                if (p.hasUpperLimit() && x0[i] + 2 * h * s >= p.upperLimit()) continue;
                idx.add(i);
                scale.add(s);
            }
            final int d = idx.size();
            flat = null;
            if (d == 0 || d > 40) return null;
            final double[][] hess = new double[d][d];
            final long r0 = fcn.stats().requests();
            final double f0 = fcn.value(x0.clone());
            final double[] fp = new double[d];
            final double[] fm = new double[d];
            for (int a = 0; a < d; a++) {
                fp[a] = fcn.value(shift(x0, idx, scale, a, h, -1, 0));
                fm[a] = fcn.value(shift(x0, idx, scale, a, -h, -1, 0));
                hess[a][a] = (fp[a] - 2 * f0 + fm[a]) / (h * h);
            }
            for (int a = 0; a < d; a++) {
                for (int b = a + 1; b < d; b++) {
                    final double fpp = fcn.value(shift(x0, idx, scale, a, h, b, h));
                    final double fpm = fcn.value(shift(x0, idx, scale, a, h, b, -h));
                    final double fmp = fcn.value(shift(x0, idx, scale, a, -h, b, h));
                    final double fmm = fcn.value(shift(x0, idx, scale, a, -h, b, -h));
                    hess[a][b] = hess[b][a] = (fpp - fpm - fmp + fmm) / (4 * h * h);
                }
            }
            final Jacobi eig = Jacobi.of(hess);
            lastCurvature = eig.values[0];
            final double up = fcn.up();
            // the scales may be poor (errors of a forced covariance): the test is relative, the step found by search
            final double big = Math.max(Math.abs(eig.values[0]), Math.abs(eig.values[d - 1]));
            final boolean down = eig.values[0] < -1e-6 * big;
            MnUserParameterState below = null;
            String what = String.format(Locale.ROOT, "smallest curvature %.3g: no direction goes down", eig.values[0]);
            if (!down && Math.abs(eig.values[0]) <= 1e-4 * big) {
                // the direction in the parameters' own units
                double norm = 0;
                for (int a = 0; a < d; a++) norm += Math.pow(eig.vectors[a][0] * scale.get(a), 2);
                norm = Math.sqrt(norm);
                final StringBuilder combo = new StringBuilder();
                for (int a = 0; a < d; a++) {
                    final double w = eig.vectors[a][0] * scale.get(a) / norm;
                    if (Math.abs(w) < 0.2) continue;
                    if (combo.length() > 0) combo.append(w > 0 ? " + " : " - ");
                    else if (w < 0) combo.append("-");
                    combo.append(String.format(Locale.ROOT, "%.2f %s", Math.abs(w), st.name(idx.get(a))));
                }
                flat = String.format(Locale.ROOT, "A flat direction of the function (curvature %.2g of the largest): %s changes "
                    + "nothing; the data do not fix it, and the errors along it are meaningless.", eig.values[0] / big, combo);
                what = String.format(Locale.ROOT, "smallest curvature %.3g: a flat direction", eig.values[0]);
            }
            if (down) {
                // along the direction, both ways, steps doubling from one Up's worth while the function keeps falling
                double fbest = f0 - 1e-4 * up;
                double tbest = 0;
                double[] xbest = null;
                for (int sign = -1; sign <= 1; sign += 2) {
                    double t = Math.min(5, Math.sqrt(2 * up / -eig.values[0]));
                    double fprev = f0;
                    for (int k = 0; k < 60; k++, t *= 2) {
                        final double[] x = x0.clone();
                        for (int a = 0; a < d; a++) {
                            final int i = idx.get(a);
                            final MinuitParameter p = st.parameter(i);
                            double v = x0[i] + sign * t * eig.vectors[a][0] * scale.get(a);
                            if (p.hasLowerLimit()) v = Math.max(v, p.lowerLimit() + 1e-3 * scale.get(a));
                            if (p.hasUpperLimit()) v = Math.min(v, p.upperLimit() - 1e-3 * scale.get(a));
                            x[i] = v;
                        }
                        final double f = fcn.value(x);
                        if (!(f < fprev)) break;
                        fprev = f;
                        if (f < fbest) {
                            fbest = f;
                            tbest = t;
                            xbest = x;
                        }
                    }
                }
                if (xbest != null) {
                    below = new MnUserParameterState(st.parameters());
                    for (int a = 0; a < d; a++) below.setValue(idx.get(a), xbest[idx.get(a)]);
                }
                what = String.format(Locale.ROOT, "curvature %.3g: a saddle point, %s", eig.values[0],
                    below != null ? String.format(Locale.ROOT, "FCN %.4g lower %.3g scale units away", f0 - fbest, tbest)
                        : "but no lower point found along it");
            }
            steps.add(new Step("Curvature check (" + d + " parameters)", m, (int) (fcn.stats().requests() - r0), what));
            return below;
        }

        private static double[] shift(double[] x0, List<Integer> idx, List<Double> scale, int a, double ha, int b, double hb) {
            final double[] x = x0.clone();
            x[idx.get(a)] += ha * scale.get(a);
            if (b >= 0) x[idx.get(b)] += hb * scale.get(b);
            return x;
        }

        /**
         * Multi-level single linkage: quasi-random points in the box, Migrad
         * from those of the lowest third with no lower point within the
         * critical distance (at most k), the minima grouped by place.
         */
        List<Basin> multistart(FunctionMinimum best, MnUserParameterState start, int k) {
            final List<Integer> var = new ArrayList<>();
            for (int i = 0; i < start.minuitParameters().size(); i++) {
                final MinuitParameter p = start.parameter(i);
                if (!p.isFixed() && !p.isConst()) var.add(i);
            }
            final int d = var.size();
            final List<MutableBasin> basins = new ArrayList<>();
            basins.add(new MutableBasin(best));
            if (d == 0) return List.of(new Basin(best, 1));
            final int n = Math.min(4000, Math.max(20 * d, 10 * k));
            final double[][] u = new double[n][d];
            final double[] f = new double[n];
            final double[][] boxes = new double[d][];
            for (int a = 0; a < d; a++) boxes[a] = box(start.parameter(var.get(a)));
            final long r0 = fcn.stats().requests();
            final double[] x = start.params();
            for (int j = 0; j < n; j++) {
                for (int a = 0; a < d; a++) {
                    u[j][a] = halton(j + 1, PRIMES[a % PRIMES.length]);
                    x[var.get(a)] = at(boxes[a], u[j][a]);
                }
                final double v = fcn.value(x.clone());
                f[j] = Double.isFinite(v) ? v : Double.POSITIVE_INFINITY;
            }
            final double r = mlslRadius(n, d);
            final Integer[] order = new Integer[n];
            for (int j = 0; j < n; j++) order[j] = j;
            Arrays.sort(order, Comparator.comparingDouble(j -> f[j]));
            final List<MnUserParameterState> starts = new ArrayList<>();
            for (int c = 0; c < Math.max(1, n / 3) && starts.size() < k; c++) {
                final int j = order[c];
                if (Double.isInfinite(f[j])) break;
                boolean lowerNear = false;
                for (int i = 0; i < n && !lowerNear; i++) {
                    if (f[i] < f[j] && dist(u[i], u[j]) < r) lowerNear = true;
                }
                if (lowerNear) continue;
                final MnUserParameterState st = new MnUserParameterState(start);
                for (int a = 0; a < d; a++) st.setValue(var.get(a), at(boxes[a], u[j][a]));
                starts.add(st);
            }
            steps.add(new Step(String.format(Locale.ROOT, "Multistart: %d quasi-random points, %d chosen", n, starts.size()),
                best, (int) (fcn.stats().requests() - r0), String.format(Locale.ROOT, "critical distance %.3g of the box", r)));

            final List<FunctionMinimum> found = new ArrayList<>();
            if (opt.parallel() && starts.size() > 1) {
                final int threads = Math.min(starts.size(), Runtime.getRuntime().availableProcessors());
                final List<Future<FunctionMinimum>> futures = new ArrayList<>();
                try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
                    for (MnUserParameterState st : starts) {
                        futures.add(pool.submit(() -> new MnMigrad(fcn, prepared(st), new MnStrategy(opt.strategy()))
                            .minimize(maxfcn, tolerance)));
                    }
                    for (int s = 0; s < futures.size(); s++) {
                        final String what = "Migrad from start " + (s + 1) + "/" + starts.size() + " (in parallel)";
                        try {
                            final FunctionMinimum m = futures.get(s).get();
                            steps.add(new Step(what, m, m.nfcn(), outcome(m)));
                            found.add(m);
                        } catch (ExecutionException e) {
                            steps.add(new Step(what, null, 0, "failed: " + e.getCause()));
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("multistart interrupted", e);
                }
            } else {
                for (int s = 0; s < starts.size(); s++) {
                    found.add(migrad("Migrad from start " + (s + 1) + "/" + starts.size(), starts.get(s), opt.strategy(), maxfcn));
                }
            }
            for (FunctionMinimum m : found) {
                if (m == null || !m.hasValidParameters() || !Double.isFinite(m.fval())) continue;
                MutableBasin home = null;
                for (MutableBasin b : basins) {
                    if (sameBasin(b.min, m)) {
                        home = b;
                        break;
                    }
                }
                if (home == null) {
                    basins.add(new MutableBasin(m));
                } else {
                    home.hits++;
                    home.min = better(home.min, m);
                }
            }
            // valid first, then the lowest; the sort is stable, so a tie keeps the main run's minimum first
            basins.sort(Comparator.comparing((MutableBasin b) -> !b.min.isValid()).thenComparingDouble(b -> fv(b.min)));
            final List<Basin> out = new ArrayList<>();
            for (MutableBasin b : basins) out.add(new Basin(b.min, b.hits));
            return out;
        }

        /** What the run learned, for the diagnosis. */
        List<Finding> findings(FunctionMinimum best, List<Basin> basins) {
            final List<Finding> out = new ArrayList<>();
            final SmartFCN.Stats s = fcn.stats();
            if (s.nonFinite() > 0) {
                final List<String> t = new ArrayList<>();
                for (SmartFCN.DomainHint h : limits) t.add(h.text());
                final List<String> sug = new ArrayList<>();
                if (limits.isEmpty()) for (SmartFCN.DomainHint h : fcn.domainHints(best.userState())) sug.add(h.text());
                out.add(new Finding(limits.isEmpty() ? Grade.RELIABLE : Grade.CAUTION, String.format(Locale.ROOT,
                    "The function was not finite (NaN or infinite) at %d of the points asked: it is not defined everywhere "
                        + "Minuit looked%s.", s.nonFinite(),
                    !t.isEmpty() ? "; the autopilot added the limits " + String.join(", ", t) + " that its values imply"
                        : !sug.isEmpty() ? "; limits " + String.join(", ", sug) + " would keep it inside" : ""),
                    limits.isEmpty() ? null : "limits added to stay in the function's domain"));
            }
            if (noise != null) {
                out.add(Finding.info("Noise: " + noise.text() + "."));
                if (noise.found() && noise.sigma() > 0) {
                    // a noise sigma_F moves the minimum of F = Up (x/err)^2 by about err sqrt(sigma_F / Up)
                    final double shift = Math.sqrt(noise.sigma() / fcn.up());
                    out.add(shift < 0.1 ? Finding.info(String.format(Locale.ROOT, "The noise moves the minimum by about %.2g "
                        + "errors: negligible.", shift))
                        : new Finding(Grade.CAUTION, String.format(Locale.ROOT, "The noise moves the minimum by about %.2g errors: "
                            + "add it to the errors, or compute the function more precisely.", shift),
                            "the function's noise shifts the minimum"));
                }
                if (precision > 0) {
                    out.add(Finding.info(String.format(Locale.ROOT, "Minuit's precision was set to %.2g (SET EPS) so that its "
                        + "numerical derivatives step above the noise.", precision)));
                }
                if (tolerance != opt.tolerance()) {
                    out.add(new Finding(Grade.CAUTION, String.format(Locale.ROOT, "The tolerance was raised from %.3g to %.3g: the "
                        + "noise makes an EDM below %.3g meaningless; the minimum is known to the noise, not better.",
                        opt.tolerance(), tolerance, 0.002 * opt.tolerance() * fcn.up()), "converged only to the noise level"));
                }
            }
            if (flat != null) out.add(new Finding(Grade.CAUTION, flat, "a direction the data do not constrain"));
            for (double[] e : escapes) {
                out.add(Finding.info(String.format(Locale.ROOT, "Migrad had stopped on a saddle point (FCN = %.8g, curvature %.3g "
                    + "along one direction): the autopilot stepped down along it to the minimum at FCN = %.8g.", e[0], e[2], e[1])));
            }
            final double[] before = migradErrors.get(best);
            if (before != null && best.hasAccurateCovar()) {
                final double[] after = best.userState().errors();
                int worst = -1;
                double ratio = 1;
                for (int i = 0; i < Math.min(before.length, after.length); i++) {
                    if (!(before[i] > 0) || !(after[i] > 0) || best.userState().parameter(i).isFixed()) continue;
                    final double q = Math.max(after[i] / before[i], before[i] / after[i]);
                    if (q > ratio) {
                        ratio = q;
                        worst = i;
                    }
                }
                if (worst >= 0 && ratio > 1.3) {
                    out.add(Finding.info(String.format(Locale.ROOT, "Migrad's approximate error of %s was %.3g, Hesse's is %.3g: "
                        + "quote Hesse's (Migrad's covariance is only an estimate built on the way).",
                        best.userState().name(worst), before[worst], after[worst])));
                }
            }
            if (basins.size() > 1 || basins.get(0).hits() > 1) {
                int starts = 0;
                for (Basin b : basins) starts += b.hits();
                int same = 0;
                for (Basin b : basins) {
                    if (b.minimum() != best && b.minimum().isValid() && Math.abs(b.minimum().fval() - best.fval()) < 1e-3 * best.up()) {
                        same++;
                    }
                }
                out.add(Finding.info(String.format(Locale.ROOT, "%d minim%s from %d starts; the lowest was reached from %d of them.",
                    basins.size(), basins.size() > 1 ? "a" : "um", starts, basins.get(0).hits())));
                if (same > 0) {
                    out.add(new Finding(Grade.CAUTION, same + " other minim" + (same > 1 ? "a are" : "um is") + " as deep as the "
                        + "best at another place: the function has a symmetry (a sign, an exchange of parameters); the errors "
                        + "describe only the minimum kept.", "equally deep minima elsewhere"));
                }
            }
            return out;
        }
    }

    private static final class MutableBasin {
        FunctionMinimum min;
        int hits = 1;

        MutableBasin(FunctionMinimum m) {
            min = m;
        }
    }

    private static double dist(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += (a[i] - b[i]) * (a[i] - b[i]);
        return Math.sqrt(s);
    }

    /** The critical distance of MLSL in the unit box: (Gamma(1 + d/2) sigma ln N / N)^(1/d) / sqrt(pi), sigma = 2. */
    static double mlslRadius(int n, int d) {
        return Math.pow(Math.exp(ChiSquare.lnGamma(1 + 0.5 * d)) * 2 * Math.log(n) / n, 1. / d) / Math.sqrt(Math.PI);
    }

    /* ---- diagnosis ---------------------------------------------------------------- */

    /** The diagnosis of a minimum, in sentences. */
    public static List<String> diagnose(FunctionMinimum min, List<MinosError> minos, List<FunctionMinimum> others) {
        final List<String> out = new ArrayList<>();
        for (Finding f : findings(min, minos, others)) out.add(f.text());
        return out;
    }

    /** How far a minimum can be trusted, from what the diagnosis finds. */
    public static Verdict judge(FunctionMinimum min, List<MinosError> minos) {
        return verdict(findings(min, minos, List.of()));
    }

    static Verdict verdict(List<Finding> findings) {
        Grade g = Grade.RELIABLE;
        final List<String> reasons = new ArrayList<>();
        for (Finding f : findings) {
            if (f.severity().ordinal() > g.ordinal()) g = f.severity();
            if (f.reason() != null && !reasons.contains(f.reason())) reasons.add(f.reason());
        }
        return new Verdict(g, reasons);
    }

    static List<Finding> findings(FunctionMinimum min, List<MinosError> minos, List<FunctionMinimum> others) {
        final List<Finding> out = new ArrayList<>();
        final MnUserParameterState st = min.userState();
        if (min.isValid()) {
            out.add(Finding.info(String.format(Locale.ROOT, "The minimum is valid: FCN = %.10g, EDM = %.3g (below the tolerance), "
                + "%d calls.", min.fval(), min.edm(), min.nfcn())));
        } else {
            out.add(new Finding(Grade.UNRELIABLE, "The minimum is NOT valid: " + outcome(min) + ". The values are the best "
                + "point reached; their errors are not to be trusted.", "the minimum is not valid"));
        }
        out.add(switch (st.covarianceStatus()) {
            case 3 -> Finding.info("The covariance is accurate (full Hesse, positive-definite).");
            case 2 -> new Finding(Grade.CAUTION, "The covariance was forced positive-definite: the function is not a parabola "
                + "near the minimum, or two parameters are degenerate; the errors are approximate.", "covariance forced positive-definite");
            case 1 -> new Finding(Grade.CAUTION, "The covariance is approximate (from Migrad's updates, not from Hesse).",
                "covariance approximate");
            case 0 -> new Finding(Grade.UNRELIABLE, "The covariance is not positive-definite: the point may be a saddle, not a "
                + "minimum.", "covariance not positive-definite");
            default -> new Finding(Grade.UNRELIABLE, "There is no covariance.", "no covariance");
        });
        // limits
        for (int i = 0; i < st.minuitParameters().size(); i++) {
            final MinuitParameter p = st.parameter(i);
            if (p.isFixed() || p.isConst() || !p.hasLimits()) continue;
            final double e = Math.max(p.error(), 1e-300);
            if (p.hasLowerLimit() && Math.abs(p.value() - p.lowerLimit()) < e) {
                out.add(new Finding(Grade.CAUTION, String.format(Locale.ROOT, "%s = %.6g is within one error of its lower limit "
                    + "%.6g: its error is not Gaussian; prefer Minos, or move the limit if it is not physical.", p.name(), p.value(),
                    p.lowerLimit()), "a parameter at its limit"));
            }
            if (p.hasUpperLimit() && Math.abs(p.upperLimit() - p.value()) < e) {
                out.add(new Finding(Grade.CAUTION, String.format(Locale.ROOT, "%s = %.6g is within one error of its upper limit "
                    + "%.6g: its error is not Gaussian; prefer Minos, or move the limit if it is not physical.", p.name(), p.value(),
                    p.upperLimit()), "a parameter at its limit"));
            }
        }
        if (st.hasCovariance() && st.covarianceStatus() >= 1) {
            final int n = st.variableParameters();
            // the correlations of the parameters themselves (the external covariance), not of Minuit's internal ones
            final MnUserCovariance c = st.covariance();
            final double[][] corr = new double[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    final double d = Math.sqrt(Math.abs(c.get(i, i) * c.get(j, j)));
                    corr[i][j] = d > 0 ? c.get(i, j) / d : i == j ? 1 : 0;
                }
            }
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (Math.abs(corr[i][j]) > 0.95) {
                        out.add(new Finding(Grade.CAUTION, String.format(Locale.ROOT, "%s and %s are %.1f%% correlated: the data fix "
                            + "mostly a combination of them; a reparametrization (or fixing one) makes the fit more robust.",
                            st.name(st.extOfInt(i)), st.name(st.extOfInt(j)), 100 * corr[i][j]), "strongly correlated parameters"));
                    }
                }
            }
            final MnGlobalCorrelationCoeff g = st.globalCC();
            if (g.isValid()) {
                for (int i = 0; i < n; i++) {
                    if (g.globalCC().get(i) > 0.99) {
                        out.add(Finding.info(String.format(Locale.ROOT, "%s has a global correlation of %.4f: it is almost "
                            + "determined by the others.", st.name(st.extOfInt(i)), g.globalCC().get(i))));
                    }
                }
            }
            // flat directions: the small eigenvalues of the correlation matrix, and the parameters they mix
            if (n > 1) {
                final Jacobi eig = Jacobi.of(corr);
                for (int k = 0; k < n; k++) {
                    if (eig.values[k] < 1e-4 * n) {
                        final StringBuilder combo = new StringBuilder();
                        for (int i = 0; i < n; i++) {
                            final double w = eig.vectors[i][k];
                            if (Math.abs(w) < 0.2) continue;
                            if (combo.length() > 0) combo.append(w > 0 ? " + " : " - ");
                            else if (w < 0) combo.append("-");
                            combo.append(String.format(Locale.ROOT, "%.2f %s", Math.abs(w), st.name(st.extOfInt(i))));
                        }
                        out.add(new Finding(Grade.CAUTION, String.format(Locale.ROOT, "A flat direction (eigenvalue %.2g of the "
                            + "correlation matrix): %s is not constrained by the data.", eig.values[k], combo),
                            "a direction the data do not constrain"));
                    }
                }
            }
        }
        for (MinosError me : minos) {
            if (!me.isValid()) {
                out.add(new Finding(Grade.CAUTION, "Minos could not find both errors of " + st.name(me.parameter())
                    + (me.atLowerLimit() || me.atUpperLimit() ? " (a limit is reached)" : "")
                    + (me.lowerNewMin() || me.upperNewMin() ? " (a lower minimum was found on the way: the fit is not at the "
                        + "global minimum)" : ""), "Minos incomplete"));
                continue;
            }
            final double lo = -me.lower();
            final double up = me.upper();
            if (lo > 0 && up > 0 && Math.abs(up - lo) / (up + lo) > 0.1) {
                out.add(Finding.info(String.format(Locale.ROOT, "%s: Minos errors -%.4g +%.4g differ by %.0f%%: the function is not "
                    + "parabolic there; quote the Minos errors.", st.name(me.parameter()), lo, up,
                    100 * Math.abs(up - lo) / (0.5 * (up + lo)))));
            }
        }
        if (!others.isEmpty()) {
            out.add(Finding.info(others.size() + " other local minim" + (others.size() > 1 ? "a were" : "um was") + " found from "
                + "other starts: the function has several minima; the lowest is kept."));
        }
        return out;
    }

    /** Eigenvalues and eigenvectors of a small symmetric matrix (cyclic Jacobi), the values increasing. */
    static final class Jacobi {
        final double[] values;
        final double[][] vectors;

        private Jacobi(double[] v, double[][] w) {
            values = v;
            vectors = w;
        }

        static Jacobi of(double[][] a0) {
            final int n = a0.length;
            final double[][] a = new double[n][];
            for (int i = 0; i < n; i++) a[i] = a0[i].clone();
            final double[][] v = new double[n][n];
            for (int i = 0; i < n; i++) v[i][i] = 1;
            double scale = 0;
            for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) scale = Math.max(scale, Math.abs(a[i][j]));
            for (int sweep = 0; sweep < 100; sweep++) {
                double off = 0;
                for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) off += a[i][j] * a[i][j];
                if (off <= 1e-30 * scale * scale) break;
                for (int p = 0; p < n; p++) {
                    for (int q = p + 1; q < n; q++) {
                        if (Math.abs(a[p][q]) < 1e-300) continue;
                        final double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                        final double t = Math.signum(theta == 0 ? 1 : theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                        final double c = 1 / Math.sqrt(t * t + 1);
                        final double s = t * c;
                        for (int k = 0; k < n; k++) {
                            final double akp = a[k][p];
                            final double akq = a[k][q];
                            a[k][p] = c * akp - s * akq;
                            a[k][q] = s * akp + c * akq;
                        }
                        for (int k = 0; k < n; k++) {
                            final double apk = a[p][k];
                            final double aqk = a[q][k];
                            a[p][k] = c * apk - s * aqk;
                            a[q][k] = s * apk + c * aqk;
                        }
                        for (int k = 0; k < n; k++) {
                            final double vkp = v[k][p];
                            final double vkq = v[k][q];
                            v[k][p] = c * vkp - s * vkq;
                            v[k][q] = s * vkp + c * vkq;
                        }
                    }
                }
            }
            final Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            Arrays.sort(order, (x, y) -> Double.compare(a[x][x], a[y][y]));
            final double[] values = new double[n];
            final double[][] vectors = new double[n][n];
            for (int k = 0; k < n; k++) {
                values[k] = a[order[k]][order[k]];
                for (int i = 0; i < n; i++) vectors[i][k] = v[i][order[k]];
            }
            return new Jacobi(values, vectors);
        }
    }

    /* ---- profiles ------------------------------------------------------------------- */

    /**
     * The profile of one parameter: at each value, the function minimized over
     * the others, less the minimum; where it crosses the level (Up times the
     * factor of the confidence level), found to a thousandth of Up.
     */
    public record Profile(String name, double[] x, double[] delta, double lowCross, double highCross, double level, double cl,
                          int calls) {

        /** The text: one line per point, the crossings and the interval. */
        public String text(double up) {
            final StringBuilder s = new StringBuilder(String.format(Locale.ROOT, "Profile of %s (FCN - FCNmin, the others "
                + "minimized; the interval at %s is where it is below %.4g)%n", name, ChiSquare.describe(cl), level));
            for (int i = 0; i < x.length; i++) s.append(String.format(Locale.ROOT, "  %14.7g  %12.6g%n", x[i], delta[i]));
            s.append(String.format(Locale.ROOT, "  crossings: %s and %s (%d calls)%n", cross(lowCross), cross(highCross), calls));
            return s.toString();
        }

        private static String cross(double c) {
            return Double.isNaN(c) ? "beyond the range" : String.format(Locale.ROOT, "%.7g", c);
        }
    }

    /** Profiles parameter par over +- nsigma errors in npoints points, at 1 sigma. */
    public static Profile profile(FCNBase fcn, FunctionMinimum min, int par, int npoints, double nsigma) {
        return profile(fcn, min, par, npoints, nsigma, 0);
    }

    /** A point of a profile: the value there and the state of the others, to start the next point from. */
    private record ProfilePoint(double x, double delta, MnUserParameterState state) {
    }

    /**
     * Profiles parameter par: from the minimum outwards on each side, each
     * Migrad starting from the previous point's optimum of the others (it
     * follows the valley, and costs a few calls where a cold start costs
     * many); then each crossing of the level refined by regula falsi
     * (Illinois) between the points that bracket it.
     */
    public static Profile profile(FCNBase fcn0, FunctionMinimum min, int par, int npoints, double nsigma, double cl) {
        final SmartFCN fcn = SmartFCN.of(fcn0);
        final long r0 = fcn.stats().requests();
        final double level = fcn.up() * ChiSquare.upFactor(cl, 1);
        final MnUserParameterState best = min.userState();
        final MinuitParameter p = best.parameter(par);
        final double x0 = p.value();
        double lo = x0 - nsigma * p.error();
        double hi = x0 + nsigma * p.error();
        if (p.hasLowerLimit()) lo = Math.max(lo, p.lowerLimit());
        if (p.hasUpperLimit()) hi = Math.min(hi, p.upperLimit());
        final int np = Math.max(1, npoints);
        final double[] xs = new double[np];
        for (int k = 0; k < np; k++) xs[k] = np == 1 ? x0 : lo + (hi - lo) * k / (np - 1);
        int c = 0;
        for (int k = 1; k < np; k++) if (Math.abs(xs[k] - x0) < Math.abs(xs[c] - x0)) c = k;
        final ProfilePoint[] pts = new ProfilePoint[np];
        MnUserParameterState from = best;
        for (int k = c; k < np; k++) {
            pts[k] = profilePoint(fcn, min, par, xs[k], from);
            from = pts[k].state();
        }
        from = c < np ? pts[c].state() : best;
        for (int k = c - 1; k >= 0; k--) {
            pts[k] = profilePoint(fcn, min, par, xs[k], from);
            from = pts[k].state();
        }
        double lowCross = Double.NaN;
        double highCross = Double.NaN;
        for (int k = c; k + 1 < np && Double.isNaN(highCross); k++) {
            if (pts[k].delta() < level && pts[k + 1].delta() >= level) highCross = refine(fcn, min, par, pts[k], pts[k + 1], level);
        }
        for (int k = c; k - 1 >= 0 && Double.isNaN(lowCross); k--) {
            if (pts[k].delta() < level && pts[k - 1].delta() >= level) lowCross = refine(fcn, min, par, pts[k], pts[k - 1], level);
        }
        final double[] ds = new double[np];
        for (int k = 0; k < np; k++) ds[k] = pts[k].delta();
        return new Profile(p.name(), xs, ds, lowCross, highCross, level, cl, (int) (fcn.stats().requests() - r0));
    }

    private static ProfilePoint profilePoint(SmartFCN fcn, FunctionMinimum min, int par, double x, MnUserParameterState from) {
        final MnUserParameterState st = new MnUserParameterState(from);
        if (!st.parameter(par).isFixed()) st.fix(par);
        st.setValue(par, x);
        if (st.variableParameters() == 0) return new ProfilePoint(x, fcn.value(st.params()) - min.fval(), st);
        final FunctionMinimum m = new MnMigrad(fcn, st, new MnStrategy(1)).minimize();
        return new ProfilePoint(x, m.fval() - min.fval(), m.userState());
    }

    /** Where the profile crosses the level between an inner point (below) and an outer one (above). */
    private static double refine(SmartFCN fcn, FunctionMinimum min, int par, ProfilePoint in, ProfilePoint out, double level) {
        ProfilePoint a = in;
        ProfilePoint b = out;
        double fa = a.delta() - level;
        double fb = b.delta() - level;
        int side = 0;
        for (int it = 0; it < 20; it++) {
            final double x = (a.x() * fb - b.x() * fa) / (fb - fa);
            final ProfilePoint m = profilePoint(fcn, min, par, x, a.state());
            final double fm = m.delta() - level;
            if (Math.abs(fm) < 1e-3 * fcn.up() || Math.abs(b.x() - a.x()) < 1e-9 * (Math.abs(x) + 1e-300)) return x;
            if (fm < 0) {
                a = m;
                fa = fm;
                if (side == -1) fb *= 0.5;
                side = -1;
            } else {
                b = m;
                fb = fm;
                if (side == 1) fa *= 0.5;
                side = 1;
            }
        }
        return (a.x() * fb - b.x() * fa) / (fb - fa);
    }
}
