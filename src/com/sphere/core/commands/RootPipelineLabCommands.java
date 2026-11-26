package com.sphere.core.commands;

import com.sphere.core.rootbackend.RootPipelineLab;
import com.sphere.core.rootbackend.RootPipelineManifest;
import com.sphere.core.rootbackend.RootPipelineTemplates;
import com.sphere.core.rootbackend.RootUserPipeline;
import com.sphere.utils.AppLogger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The pipeline laboratory's commands: static review, mechanical repair, corner
 * cases, sensitivity, uncertainty, equivalence, fast-math, statelessness, a
 * full audit, a pipeline from a formula, and the bridge that makes a compiled
 * pipeline a TF1, TF2 or TF3 that ROOT can draw, integrate and fit.
 */
public final class RootPipelineLabCommands {

    private RootPipelineLabCommands() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /* ------------------------------------------------------------------ */

    private record Target(RootPipelineManifest manifest, Path source, String text) { }

    /** A pipeline's manifest, file and text, or null after saying why not. */
    private static Target target(String name, CommandExecutionContext c, boolean mustBeNumeric) {
        if (name == null || name.isBlank()) {
            return null;
        }
        final Path source = RootEngineCommands.pipelineSource(name, RootEngineCommands.activeProject(c));
        if (source == null) {
            AppLogger.error("No pipeline named " + name + " in includes/. :root pipeline list says which there are.");
            return null;
        }
        final RootPipelineManifest manifest = RootPipelineManifest.fromSource(source);
        if (manifest == null) {
            AppLogger.error(name + " does not declare itself as a pipeline.");
            return null;
        }
        if (mustBeNumeric && !RootPipelineTemplates.canBeTimed(manifest)) {
            AppLogger.error(name + " is a " + manifest.kind.token
                + ": only a transform or a filter taking numbers can be run by the lab.");
            return null;
        }
        try {
            return new Target(manifest, source, Files.readString(source, StandardCharsets.UTF_8));
        } catch (java.io.IOException unreadable) {
            AppLogger.error("Could not read " + source + ": " + unreadable.getMessage());
            return null;
        }
    }

    private static RootPipelineLab.Bench bench(Target t, CommandExecutionContext c, List<String> extra) {
        AppLogger.info("Building the lab for " + t.manifest.name
            + (extra == null || extra.isEmpty() ? "" : " with " + String.join(" ", extra)) + "...");
        final RootPipelineLab.Bench b = RootPipelineLab.build(RootEngineCommands.compilerFor(c),
            t.manifest, t.source, extra);
        if (!b.built) {
            AppLogger.error(b.problem);
        }
        return b;
    }

    private static String inputNames(RootPipelineManifest m) {
        final StringBuilder out = new StringBuilder();
        for (var input : m.inputs) {
            out.append(out.length() > 0 ? ", " : "").append(input.name());
        }
        return out.toString();
    }

    private static String g(double v) {
        return String.format(Locale.ROOT, "%.6g", v);
    }

    /* ------------------------------------------------------------------ */
    /* static review and repair                                            */
    /* ------------------------------------------------------------------ */

    /** Reads the source for the classic mistakes and grades it. */
    public static void lint(String i, CommandExecutionContext c) {
        final String name = Handlers.head(Handlers.args(i, ":root pipeline lint"));
        if (name.isEmpty()) {
            Handlers.usage(":root pipeline lint <name>");
            return;
        }
        final Target t = target(name, c, false);
        if (t == null) {
            return;
        }
        final List<RootPipelineLab.Finding> found = RootPipelineLab.lint(t.text, t.manifest);
        if (found.isEmpty()) {
            AppLogger.success(t.manifest.name + ": nothing to say. Grade A (100/100).");
            return;
        }
        int fixable = 0;
        for (var f : found) {
            final String line = "  " + f;
            switch (f.severity()) {
                case ERROR -> AppLogger.error(line);
                case WARNING -> AppLogger.warn(line);
                default -> AppLogger.raw(line);
            }
            if (f.fixable()) {
                fixable++;
            }
        }
        final int score = RootPipelineLab.score(found);
        AppLogger.result(t.manifest.name + ": " + found.size() + " findings, grade "
            + RootPipelineLab.grade(score) + " (" + score + "/100)."
            + (fixable > 0 ? " " + fixable + " can be repaired: :root pipeline fix " + t.manifest.name : ""));
    }

    /** Applies the safe rewrites, keeping the previous version in the history. */
    public static void fix(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pipeline fix"));
        if (w.length < 1) {
            Handlers.usage(":root pipeline fix <name> [--dry]");
            return;
        }
        final Target t = target(w[0], c, false);
        if (t == null) {
            return;
        }
        final boolean dry = w.length > 1 && w[1].startsWith("--dry");
        final RootPipelineLab.Fixed fixed = RootPipelineLab.fix(t.text);
        if (fixed.changes().isEmpty()) {
            AppLogger.result("Nothing to repair mechanically in " + t.manifest.name + ".");
            return;
        }
        for (String change : fixed.changes()) {
            AppLogger.raw("  " + change);
        }
        if (dry) {
            AppLogger.result(fixed.changes().size() + " changes proposed, none written (--dry).");
            return;
        }
        try {
            com.sphere.core.rootbackend.RootPipelineHistory.keep(t.source);
            Files.writeString(t.source, fixed.source(), StandardCharsets.UTF_8);
            AppLogger.success(fixed.changes().size() + " changes written to " + t.source.getFileName()
                + ". The previous version is in the history. :root pipeline build " + t.manifest.name);
        } catch (java.io.IOException failure) {
            AppLogger.error("Could not write " + t.source + ": " + failure.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* corner cases, domain, state                                         */
    /* ------------------------------------------------------------------ */

    /** Every corner value of every input: zeros, signed zeros, huge, tiny, NaN, infinities. */
    public static void fuzz(String i, CommandExecutionContext c) {
        final Target t = target(Handlers.head(Handlers.args(i, ":root pipeline fuzz")), c, true);
        if (t == null) {
            Handlers.usage(":root pipeline fuzz <name>");
            return;
        }
        try (RootPipelineLab.Bench b = bench(t, c, null)) {
            if (b.built) {
                reportFuzz(t, b.run(List.of("fuzz")));
            }
        }
    }

    /** Returns a penalty for the audit. */
    private static int reportFuzz(Target t, RootPipelineLab.Run run) {
        if (run == null) {
            AppLogger.error("The lab program could not be started.");
            return 30;
        }
        String done = null;
        int shown = 0;
        for (String line : run.lines()) {
            if (line.startsWith("bad ")) {
                if (shown++ == 0) {
                    AppLogger.raw("  answers that are not a number, for finite inputs (" + inputNames(t.manifest) + "):");
                }
                AppLogger.raw("      " + line.substring(4));
            } else if (line.startsWith("done ")) {
                done = line.substring(5);
            }
        }
        if (run.crashed()) {
            AppLogger.error("  The pipeline CRASHED" + (run.timedOut() ? " (or hung)" : " (exit " + run.exitCode() + ")")
                + " on: " + inputNames(t.manifest) + " = " + run.lastTried()
                + ". An integer division by zero or an out-of-range index is the usual cause.");
            return 30;
        }
        AppLogger.raw("  " + (done == null ? "" : done.replace("=", " ").replace("tried", "tried")));
        if (shown == 0) {
            AppLogger.success("  Every corner case answered a number.");
            return 0;
        }
        AppLogger.warn("  " + shown + (shown >= 25 ? "+" : "") + " corner cases gave NaN or infinity."
            + " Some may be the physics (log 0); the others want a guard.");
        return 5;
    }

    /** Where each input, the others held, gives a number and where it does not. */
    public static void domain(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pipeline domain"));
        if (w.length < 1) {
            Handlers.usage(":root pipeline domain <name> [low] [high] [others-at]");
            return;
        }
        final Target t = target(w[0], c, true);
        if (t == null) {
            return;
        }
        final double low = w.length > 1 ? RootEngineCommands.asDouble(w[1]) : -100;
        final double high = w.length > 2 ? RootEngineCommands.asDouble(w[2]) : 100;
        final double held = w.length > 3 ? RootEngineCommands.asDouble(w[3]) : 1;
        try (RootPipelineLab.Bench b = bench(t, c, null)) {
            if (b.built) {
                reportDomain(t, b.run(List.of("domain", Double.toString(low), Double.toString(high), "2001",
                    Double.toString(held))), held);
            }
        }
    }

    private static int reportDomain(Target t, RootPipelineLab.Run run, double held) {
        if (run == null || run.crashed()) {
            AppLogger.error("  The domain sweep did not finish" + (run == null ? "." : ": exit " + run.exitCode() + "."));
            return 10;
        }
        int gaps = 0;
        AppLogger.raw("  each input alone, the others at " + g(held) + ":");
        for (String line : run.lines()) {
            final String[] p = line.trim().split("\\s+");
            if (p.length < 5 || !p[0].equals("seg")) {
                continue;
            }
            final int k = Integer.parseInt(p[1]);
            final boolean ok = p[4].equals("1");
            if (!ok) {
                gaps++;
            }
            AppLogger.raw(String.format(Locale.ROOT, "      %-14s [%s, %s]  %s",
                k < t.manifest.inputs.size() ? t.manifest.inputs.get(k).name() : "#" + k,
                p[2], p[3], ok ? "a number" : "NaN or infinite"));
        }
        if (gaps == 0) {
            AppLogger.success("  Defined everywhere in the range.");
        }
        return gaps > 0 ? 2 : 0;
    }

    /** Checks the thread-safety claim: same inputs, other order, same bits. */
    public static void state(String i, CommandExecutionContext c) {
        final Target t = target(Handlers.head(Handlers.args(i, ":root pipeline state")), c, true);
        if (t == null) {
            Handlers.usage(":root pipeline state <name>");
            return;
        }
        try (RootPipelineLab.Bench b = bench(t, c, null)) {
            if (b.built) {
                reportState(t, b.run(List.of("state")));
            }
        }
    }

    private static int reportState(Target t, RootPipelineLab.Run run) {
        if (run == null || run.crashed()) {
            AppLogger.error("  The statelessness check did not finish.");
            return 10;
        }
        final double differ = run.value("differ");
        if (differ == 0) {
            AppLogger.success("  Stateless: " + (long) run.value("calls")
                + " calls in two orders gave the same bits."
                + (t.manifest.threadSafe ? " The thread-safe claim holds." : " It could be declared thread safe."));
            return 0;
        }
        for (String line : run.lines()) {
            if (line.startsWith("example ")) {
                AppLogger.raw("      " + line.substring(8));
            }
        }
        if (t.manifest.threadSafe) {
            AppLogger.error("  " + (long) differ + " answers changed with the call order: the pipeline keeps state,"
                + " yet it is declared thread safe. :root mt on would give wrong results.");
            return 25;
        }
        AppLogger.warn("  " + (long) differ + " answers depend on call order: the pipeline keeps state"
            + " (it is correctly declared not thread safe).");
        return 0;
    }

    /* ------------------------------------------------------------------ */
    /* sensitivity and uncertainty                                         */
    /* ------------------------------------------------------------------ */

    /** Partial derivatives, curvature and elasticity at a point, ranked. */
    public static void grad(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pipeline grad"));
        if (w.length < 1) {
            Handlers.usage(":root pipeline grad <name> [x1 x2 ...]");
            return;
        }
        final Target t = target(w[0], c, true);
        if (t == null) {
            return;
        }
        final int n = t.manifest.inputs.size();
        final List<String> args = new ArrayList<>(List.of("grad"));
        final double[] x = new double[n];
        for (int k = 0; k < n; k++) {
            x[k] = k + 1 < w.length ? RootEngineCommands.asDouble(w[k + 1]) : 1.0;
            args.add(Double.toString(x[k]));
        }
        try (RootPipelineLab.Bench b = bench(t, c, null)) {
            if (!b.built) {
                return;
            }
            final RootPipelineLab.Run run = b.run(args);
            if (run == null || run.crashed()) {
                AppLogger.error("The pipeline did not survive the evaluation at that point.");
                return;
            }
            final double f = run.value("f");
            AppLogger.raw("  f(" + inputNames(t.manifest) + ") = " + g(f) + " at " + java.util.Arrays.toString(x));
            record Row(String name, double d, double dd, double elasticity) { }
            final List<Row> rows = new ArrayList<>();
            for (String line : run.lines()) {
                final String[] p = line.trim().split("\\s+");
                if (p.length >= 3 && p[0].equals("d")) {
                    final int k = Integer.parseInt(p[1]);
                    if (p[2].equals("discrete")) {
                        AppLogger.raw("      " + t.manifest.inputs.get(k).name() + "  is discrete: no derivative");
                        continue;
                    }
                    final double d = RootPipelineLab.number(p[2]);
                    final double dd = p.length > 3 ? RootPipelineLab.number(p[3]) : Double.NaN;
                    final double e = f != 0 ? d * x[k] / f : Double.NaN;
                    rows.add(new Row(t.manifest.inputs.get(k).name(), d, dd, e));
                }
            }
            rows.sort((a, bb) -> Double.compare(Math.abs(bb.elasticity()), Math.abs(a.elasticity())));
            AppLogger.raw(String.format(Locale.ROOT, "  %-14s %-14s %-14s %s", "input", "df/dx", "d2f/dx2",
                "elasticity (% change of f per % of x)"));
            for (Row r : rows) {
                AppLogger.raw(String.format(Locale.ROOT, "      %-12s %-14s %-14s %s", r.name(), g(r.d()), g(r.dd()),
                    Double.isFinite(r.elasticity()) ? g(r.elasticity()) : "--"));
            }
            if (!rows.isEmpty() && Double.isFinite(rows.get(0).elasticity())) {
                AppLogger.success("  The answer is most sensitive to " + rows.get(0).name()
                    + ": a 1% change of it moves f by " + g(Math.abs(rows.get(0).elasticity())) + "%.");
            }
            for (Row r : rows) {
                if (!Double.isFinite(r.d())) {
                    AppLogger.warn("  The derivative along " + r.name() + " is not a number: an edge or a kink at this point.");
                }
            }
        }
    }

    /**
     * Inputs with errors in, the answer's error out: Monte Carlo with the
     * quantiles, against the linear propagation everybody writes by hand.
     * When the two disagree the function bends inside the error bars, and the
     * linear formula is not to be trusted.
     */
    public static void propagate(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pipeline propagate"));
        if (w.length < 1) {
            Handlers.usage(":root pipeline propagate <name> <v1+-s1> <v2+-s2> ... [n=200000]");
            return;
        }
        final Target t = target(w[0], c, true);
        if (t == null) {
            return;
        }
        final int n = t.manifest.inputs.size();
        long count = 200_000;
        final List<double[]> given = new ArrayList<>();
        for (int k = 1; k < w.length; k++) {
            if (w[k].startsWith("n=")) {
                count = Math.max(1000, (long) RootEngineCommands.asDouble(w[k].substring(2)));
                continue;
            }
            final String[] p = w[k].split("\\+-|±|\\+/-|:", 2);
            given.add(new double[] {RootEngineCommands.asDouble(p[0]),
                p.length > 1 ? Math.abs(RootPipelineLab.number(p[1])) : 0.0});
        }
        if (given.size() != n) {
            AppLogger.error(t.manifest.name + " takes " + n + " inputs (" + inputNames(t.manifest)
                + "); give each as value+-error, for example 91.2+-0.5.");
            return;
        }
        final List<String> args = new ArrayList<>(List.of("mc", Long.toString(count), "12345"));
        for (double[] one : given) {
            args.add(Double.toString(one[0]));
            args.add(Double.toString(Double.isNaN(one[1]) ? 0 : one[1]));
        }
        try (RootPipelineLab.Bench b = bench(t, c, null)) {
            if (!b.built) {
                return;
            }
            final RootPipelineLab.Run run = b.run(args);
            if (run == null || run.crashed()) {
                AppLogger.error("The Monte Carlo did not finish.");
                return;
            }
            final double central = run.value("central");
            final double mean = run.value("mean");
            final double sd = run.value("sd");
            final double lin = run.value("linear");
            final double q16 = run.value("q16");
            final double q50 = run.value("q50");
            final double q84 = run.value("q84");
            final double bad = run.value("bad");
            AppLogger.raw("  " + count + " draws of (" + inputNames(t.manifest) + ")");
            AppLogger.raw("      central value      " + g(central));
            AppLogger.raw("      Monte Carlo        " + g(mean) + " +- " + g(sd));
            AppLogger.raw("      68% interval       " + g(q50) + "  -" + g(q50 - q16) + "  +" + g(q84 - q50));
            AppLogger.raw("      95% interval       [" + g(run.value("q025")) + ", " + g(run.value("q975")) + "]");
            AppLogger.raw("      linear propagation " + g(central) + " +- " + g(lin));
            AppLogger.raw("      skewness           " + g(run.value("skew")));
            AppLogger.raw("  error budget (linear, share of the variance):");
            final List<double[]> parts = new ArrayList<>();
            for (String line : run.lines()) {
                final String[] p = line.trim().split("\\s+");
                if (p.length == 3 && p[0].equals("part")) {
                    parts.add(new double[] {Integer.parseInt(p[1]), RootPipelineLab.number(p[2])});
                }
            }
            parts.sort((a, bb) -> Double.compare(Math.abs(bb[1]), Math.abs(a[1])));
            for (double[] p : parts) {
                final double share = lin > 0 ? 100 * p[1] * p[1] / (lin * lin) : 0;
                AppLogger.raw(String.format(Locale.ROOT, "      %-14s +- %-12s %5.1f %%  %s",
                    t.manifest.inputs.get((int) p[0]).name(), g(Math.abs(p[1])), share,
                    "#".repeat((int) Math.round(share / 4))));
            }
            if (bad > 0) {
                AppLogger.warn("  " + (long) bad + " draws gave no number and were left out: the errors reach outside the domain.");
            }
            final double ratio = lin > 0 ? sd / lin : Double.NaN;
            if (Double.isFinite(ratio) && (ratio > 1.1 || ratio < 0.9
                    || Math.abs(mean - central) > 0.1 * Math.max(sd, 1e-300))) {
                AppLogger.warn(String.format(Locale.ROOT,
                    "  The linear formula is NOT reliable here: it gives %s, the Monte Carlo %s (ratio %.2f), "
                    + "and the mean is shifted by %s. Quote the Monte Carlo interval.",
                    g(lin), g(sd), ratio, g(mean - central)));
            } else {
                AppLogger.success("  Linear propagation agrees with the Monte Carlo: the function is straight inside the errors.");
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* equivalence                                                         */
    /* ------------------------------------------------------------------ */

    /** Two pipelines with the same inputs, compared bit by bit on the same draws. */
    public static void diff(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pipeline diff"));
        if (w.length < 2) {
            Handlers.usage(":root pipeline diff <a> <b> [low] [high] [samples]");
            return;
        }
        final Target a = target(w[0], c, true);
        final Target b = a == null ? null : target(w[1], c, true);
        if (b == null) {
            return;
        }
        if (a.manifest.inputs.size() != b.manifest.inputs.size()) {
            AppLogger.error(a.manifest.name + " takes " + a.manifest.inputs.size() + " inputs and "
                + b.manifest.name + " takes " + b.manifest.inputs.size() + ": nothing to compare.");
            return;
        }
        final String low = w.length > 2 ? w[2] : "0";
        final String high = w.length > 3 ? w[3] : "100";
        final String n = w.length > 4 ? w[4] : "20000";
        final List<String> args = List.of("sample", n, low, high, "7");
        List<String[]> left;
        List<String[]> right;
        try (RootPipelineLab.Bench one = bench(a, c, null)) {
            if (!one.built) {
                return;
            }
            left = RootPipelineLab.samples(one.run(args));
        }
        try (RootPipelineLab.Bench two = bench(b, c, null)) {
            if (!two.built) {
                return;
            }
            right = RootPipelineLab.samples(two.run(args));
        }
        reportComparison(a.manifest.name, b.manifest.name, RootPipelineLab.compare(left, right), a.manifest);
    }

    /** -O3 against -O3 -ffast-math (and -march=native): is the shortcut safe here? */
    public static void fastmath(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pipeline fastmath"));
        if (w.length < 1) {
            Handlers.usage(":root pipeline fastmath <name> [low] [high]");
            return;
        }
        final Target t = target(w[0], c, true);
        if (t == null) {
            return;
        }
        final List<String> args = List.of("sample", "20000", w.length > 1 ? w[1] : "0", w.length > 2 ? w[2] : "100", "11");
        List<String[]> base;
        try (RootPipelineLab.Bench b = bench(t, c, List.of("-ffp-contract=off"))) {
            if (!b.built) {
                return;
            }
            base = RootPipelineLab.samples(b.run(args));
        }
        for (List<String> flags : List.of(List.of("-ffast-math"), List.of("-march=native"),
                                          List.of("-ffast-math", "-march=native"))) {
            List<String[]> other;
            try (RootPipelineLab.Bench b = bench(t, c, flags)) {
                if (!b.built) {
                    AppLogger.warn("  " + String.join(" ", flags) + " does not build here.");
                    continue;
                }
                other = RootPipelineLab.samples(b.run(args));
            }
            reportComparison("strict IEEE", String.join(" ", flags), RootPipelineLab.compare(base, other), t.manifest);
        }
        AppLogger.raw("  A few ulps are rounding. Thousands, or a NaN that became a number, mean the shortcut changes the physics.");
    }

    private static void reportComparison(String left, String right, RootPipelineLab.Comparison cmp,
                                         RootPipelineManifest m) {
        if (cmp.problem() != null) {
            AppLogger.error("  " + cmp.problem());
            return;
        }
        if (cmp.bitExact()) {
            AppLogger.success("  " + right + " vs " + left + ": identical to the bit on " + cmp.compared() + " inputs.");
            return;
        }
        final long differ = cmp.compared() - cmp.identical();
        AppLogger.warn(String.format(Locale.ROOT, "  %s vs %s: %d of %d differ, worst %s ulps, max |diff| %s, max rel %s",
            right, left, differ, cmp.compared(),
            cmp.maxUlps() == Long.MAX_VALUE ? "inf (NaN or infinity on one side)" : Long.toString(cmp.maxUlps()),
            g(cmp.maxAbs()), g(cmp.maxRel())));
        for (var d : cmp.worst()) {
            AppLogger.raw("      (" + inputNames(m) + ") = " + d.inputs() + "   " + g(d.a()) + "  vs  " + g(d.b()));
        }
    }

    /* ------------------------------------------------------------------ */
    /* the full audit                                                      */
    /* ------------------------------------------------------------------ */

    /** Lint, corner cases, statelessness and domain, then one grade. */
    public static void audit(String i, CommandExecutionContext c) {
        final Target t = target(Handlers.head(Handlers.args(i, ":root pipeline audit")), c, false);
        if (t == null) {
            Handlers.usage(":root pipeline audit <name>");
            return;
        }
        AppLogger.info("Auditing " + t.manifest.name + "  " + t.manifest.signature());
        AppLogger.raw("  [1] source review");
        final List<RootPipelineLab.Finding> found = RootPipelineLab.lint(t.text, t.manifest);
        for (var f : found) {
            AppLogger.raw("      " + f);
        }
        if (found.isEmpty()) {
            AppLogger.raw("      nothing to say");
        }
        int score = RootPipelineLab.score(found);
        if (RootPipelineTemplates.canBeTimed(t.manifest)) {
            try (RootPipelineLab.Bench b = bench(t, c, null)) {
                if (b.built) {
                    AppLogger.raw("  [2] corner cases");
                    score -= reportFuzz(t, b.run(List.of("fuzz")));
                    AppLogger.raw("  [3] statelessness");
                    score -= reportState(t, b.run(List.of("state")));
                    AppLogger.raw("  [4] domain over [-100, 100]");
                    score -= reportDomain(t, b.run(List.of("domain", "-100", "100", "801", "1")), 1);
                } else {
                    score -= 40;
                }
            }
        } else {
            AppLogger.raw("  [2-4] skipped: a " + t.manifest.kind.token + " cannot be run with invented inputs.");
        }
        score = Math.max(0, score);
        final String grade = RootPipelineLab.grade(score);
        final String verdict = "Audit of " + t.manifest.name + ": grade " + grade + " (" + score + "/100).";
        if (score >= 90) {
            AppLogger.success(verdict + " Ready for production.");
        } else if (score >= 60) {
            AppLogger.warn(verdict + " Usable; read the findings above. :root pipeline fix repairs the mechanical ones.");
        } else {
            AppLogger.error(verdict + " Do not fill a histogram with it yet.");
        }
    }

    /* ------------------------------------------------------------------ */
    /* a pipeline from a formula                                           */
    /* ------------------------------------------------------------------ */

    /** Writes, builds and loads a transform from one line of mathematics. */
    public static void formula(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root pipeline formula");
        final String name = Handlers.head(a);
        final String expr = Handlers.tail(a);
        if (name.isEmpty() || expr.isEmpty()) {
            Handlers.usage(":root pipeline formula <name> <expression>   e.g. :root pipeline formula mt sqrt(2*pt1*pt2*(1-cos(phi1-phi2)))");
            return;
        }
        final RootPipelineLab.FromFormula made = RootPipelineLab.fromFormula(name, expr.replaceFirst("\\s+--force$", ""));
        if (made.problem() != null) {
            // Nothing is written: a source that cannot compile helps no one.
            RootEngineCommands.printProblem(made.problem());
            return;
        }
        final String why = made.manifest().validate(null);
        if (why != null) {
            AppLogger.error(why);
            return;
        }
        final Path layer = RootUserPipeline.globalRoot();
        RootUserPipeline.ensureLayout(layer);
        final Path file = made.manifest().sourceIn(layer);
        if (Files.exists(file) && !expr.endsWith("--force")) {
            AppLogger.error(file.getFileName() + " already exists. Add --force to replace it (the old one goes to the history).");
            return;
        }
        try {
            com.sphere.core.rootbackend.RootPipelineHistory.keep(file);
            Files.writeString(file, made.source(), StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            AppLogger.error("Could not write " + file + ": " + failure.getMessage());
            return;
        }
        AppLogger.success("Written " + file.getFileName() + ":  " + made.manifest().signature());
        for (String note : made.notes()) {
            AppLogger.warn("  " + note);
        }
        for (var f : RootPipelineLab.lint(made.source(), made.manifest())) {
            AppLogger.warn("  " + f);
        }
        Handlers.runBuilds(new ArrayList<>(List.of(file)), RootEngineCommands.activeProject(c), c);
        AppLogger.raw("  Once loaded:  :root " + name + " " + "<" + inputNames(made.manifest()).replace(", ", "> <") + ">"
            + "   or  :root pipeline tf " + name);
    }

    /* ------------------------------------------------------------------ */
    /* a pipeline as a ROOT function                                       */
    /* ------------------------------------------------------------------ */

    /**
     * Makes a loaded pipeline a TF1, TF2 or TF3.
     *
     * Up to three inputs become the variables; every other input becomes a
     * parameter, named after it and set to the value given. The result is an
     * ordinary ROOT function: it draws, integrates, finds its maximum, is cut
     * into a TF12, and -- the point -- it is fitted, so the compiled C++ model
     * itself is what meets the data, with its other inputs as fit parameters.
     */
    public static void tf(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pipeline tf"));
        if (w.length < 1) {
            Handlers.usage(":root pipeline tf <name> [vars=a,b,c] [range=lo:hi,lo:hi] [at=v1,v2,...] [as=fname]");
            return;
        }
        final var loaded = RootUserPipeline.pipeline(w[0]);
        if (loaded == null || loaded.manifest() == null) {
            AppLogger.error("No pipeline named " + w[0] + " is loaded. :root pipeline load " + w[0]);
            return;
        }
        final RootPipelineManifest m = loaded.manifest();
        if (!RootPipelineTemplates.canBeTimed(m)) {
            AppLogger.error(m.name + " must be a transform or a filter taking numbers to become a function.");
            return;
        }
        String vars = null;
        String range = "";
        String at = "";
        String as = m.name + "_tf";
        for (int k = 1; k < w.length; k++) {
            if (w[k].startsWith("vars=")) {
                vars = w[k].substring(5);
            } else if (w[k].startsWith("range=")) {
                range = w[k].substring(6);
            } else if (w[k].startsWith("at=")) {
                at = w[k].substring(3);
            } else if (w[k].startsWith("as=")) {
                as = w[k].substring(3);
            }
        }
        final List<Integer> variables = new ArrayList<>();
        if (vars == null) {
            variables.add(0);
        } else {
            for (String v : vars.split(",")) {
                int found = -1;
                for (int k = 0; k < m.inputs.size(); k++) {
                    if (m.inputs.get(k).name().equalsIgnoreCase(v.trim())) {
                        found = k;
                    }
                }
                if (found < 0) {
                    AppLogger.error(m.name + " has no input called " + v + ". Its inputs: " + inputNames(m));
                    return;
                }
                if (!variables.contains(found)) {
                    variables.add(found);
                }
            }
        }
        if (variables.size() > 3) {
            AppLogger.error("ROOT functions have at most three variables.");
            return;
        }
        final String[] ranges = range.isEmpty() ? new String[0] : range.split(",");
        final String[] atValues = at.isEmpty() ? new String[0] : at.split(",");

        final StringBuilder argsCpp = new StringBuilder();
        final List<Integer> params = new ArrayList<>();
        for (int k = 0; k < m.inputs.size(); k++) {
            final String type = m.inputs.get(k).type();
            final int v = variables.indexOf(k);
            final String value;
            if (v >= 0) {
                value = "x[" + v + "]";
            } else {
                value = "p[" + params.size() + "]";
                params.add(k);
            }
            argsCpp.append(k > 0 ? ", " : "").append('(').append(type).append(") ").append(value);
        }
        final String cls = switch (variables.size()) {
            case 2 -> "TF2";
            case 3 -> "TF3";
            default -> "TF1";
        };
        final StringBuilder rangeCpp = new StringBuilder();
        for (int v = 0; v < variables.size(); v++) {
            final String[] lohi = v < ranges.length ? ranges[v].split(":") : new String[0];
            rangeCpp.append(lohi.length == 2 ? lohi[0] + ", " + lohi[1] : "0, 100").append(", ");
        }
        final StringBuilder setup = new StringBuilder();
        for (int p = 0; p < params.size(); p++) {
            setup.append("f->SetParName(").append(p).append(", \"").append(m.inputs.get(params.get(p)).name()).append("\"); ");
            setup.append("f->SetParameter(").append(p).append(", ")
                 .append(p < atValues.length ? atValues[p] : "1.0").append("); ");
        }
        final StringBuilder varNames = new StringBuilder();
        for (int v = 0; v < variables.size(); v++) {
            varNames.append(v > 0 ? ", " : "").append("xyz".charAt(v)).append(" = ")
                    .append(m.inputs.get(variables.get(v)).name());
        }
        RootFunctionCommands.prepare(c);
        Handlers.cling(c, "([]() { if (TObject *old = gROOT->GetListOfFunctions()->FindObject(\"" + as + "\")) {"
            + " gROOT->GetListOfFunctions()->Remove(old); }"
            + " " + cls + " *f = new " + cls + "(\"" + as + "\", [](double *x, double *p) { (void) p; return (double) (::"
            + m.name + "(" + argsCpp + ")); }, " + rangeCpp + params.size() + "); f->SetNpx(400); " + setup
            + " std::ostringstream o; o << f->GetName() << \"  " + cls + " of the pipeline " + m.name + ",  " + varNames
            + "\"; for (int k = 0; k < f->GetNpar(); ++k) o << \"\\n  parameter \" << f->GetParName(k) << \" = \" << f->GetParameter(k);"
            + " return o.str(); }())");
        AppLogger.raw("  Draw it:  :root " + (variables.size() == 1 ? "func" : "tf" + variables.size()) + " draw " + as
            + "     Evaluate:  :root tf eval " + as + " ...");
        if (!params.isEmpty()) {
            AppLogger.raw("  Fit it:   :root hist fit <histogram> " + as + "   (the parameters are the other inputs)");
        }
        if (variables.size() == 2) {
            AppLogger.raw("  Slice it: :root tf12 new " + as + "_cut " + as + " <value> x    or    :root tf12 slices " + as);
        }
    }
}
