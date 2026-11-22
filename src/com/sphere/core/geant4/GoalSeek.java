package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Goal seeking for a detector, as Creo's Behavioral Modeling does it for a part: a physics target (95% of a 50 GeV
 * electron kept, 25 X0, a mass under two tonnes) and a variable of the design; the value meeting the target is
 * searched on a copy of the design, so the document changes only when the answer is applied, as one step.
 *
 * <p>The metric is taken as monotonic in the variable over the range searched (more layers keep more): the search
 * brackets the target by doubling steps from the present value, then halves the bracket. A count (a whole number
 * with no unit) moves by whole steps. For "at least" and "at most" the answer is the boundary, the least change that
 * meets the target: when the design already meets it, the boundary tells by how much.
 */
public final class GoalSeek {

    private GoalSeek() {
    }

    /** What a goal measures, on the beam's report or on the design. */
    public enum Metric {
        CONTAINMENT("energy kept", "%"),
        LONGITUDINAL("kept in depth", "%"),
        LATERAL("kept across", "%"),
        DEPTH_X0("depth", "X0"),
        DEPTH_LAMBDA("depth", "λI"),
        DEPTH("depth", "mm"),
        SAMPLING("sampling fraction (mip)", "%"),
        RESOLUTION("stochastic term", "%/√GeV"),
        MIP_SIGNAL("mip signal", "MeV"),
        MASS("mass", "kg");

        public final String label;
        public final String unit;

        Metric(String label, String unit) {
            this.label = label;
            this.unit = unit;
        }

        /** The metric's value, in its unit; NaN when the beam's particle has none (a muon's containment). */
        public double of(BeamPhysics.Report r, Geometry g) {
            return switch (this) {
                case CONTAINMENT -> 100 * r.contained();
                case LONGITUDINAL -> 100 * r.longitudinal();
                case LATERAL -> 100 * r.lateral();
                case DEPTH_X0 -> r.hit() ? r.x0() : Double.NaN;
                case DEPTH_LAMBDA -> r.hit() ? r.lambda() : Double.NaN;
                case DEPTH -> r.hit() ? r.depth() : Double.NaN;
                case SAMPLING -> r.sampling() != null ? 100 * r.sampling().mip() : Double.NaN;
                case RESOLUTION -> r.sampling() != null ? 100 * r.sampling().stochastic() : Double.NaN;
                case MIP_SIGNAL -> r.hit() ? r.mipVisible() : Double.NaN;
                case MASS -> detectorMass(g) / Units.KILOGRAM;
            };
        }

        /** "95.2 %", "25.1 X0", "312 kg". */
        public String format(double v) {
            if (Double.isNaN(v)) {
                return "n/a";
            }
            final String num = this == DEPTH ? Units.best(v, Units.Category.LENGTH)
                : String.format(Locale.ROOT, Math.abs(v) >= 100 ? "%.0f" : Math.abs(v) >= 10 ? "%.1f" : "%.2f", v);
            return this == DEPTH ? num : num + " " + unit;
        }

        /** By a word: containment, longitudinal, lateral, x0, lambda, depth, sampling, resolution, signal, mass. */
        public static Metric of(String word) {
            final String w = word.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
            return switch (w) {
                case "containment", "contained", "contain", "kept", "keep", "energy", "leakage" -> CONTAINMENT;
                case "longitudinal", "depthcontainment" -> LONGITUDINAL;
                case "lateral", "transverse" -> LATERAL;
                case "x0", "radiationlength", "radiationlengths", "radlen" -> DEPTH_X0;
                case "lambda", "lambdai", "interactionlength", "interactionlengths" -> DEPTH_LAMBDA;
                case "depth", "length", "thickness" -> DEPTH;
                case "sampling", "samplingfraction", "fsamp" -> SAMPLING;
                case "resolution", "stochastic", "stochasticterm" -> RESOLUTION;
                case "signal", "mip", "mipsignal" -> MIP_SIGNAL;
                case "mass", "weight" -> MASS;
                default -> null;
            };
        }

        /** A target as typed for this metric: 95%, 25, 30cm, 2t, 500 kg. */
        public double parse(String text, Expr.Scope scope) throws GeometryException {
            String t = text.trim().replace("%", "").replace("λI", "").replaceAll("(?i)x0$|lambdai?$", "").trim();
            return switch (this) {
                case DEPTH -> Expr.eval(Inputs.normalize(t, Units.Category.LENGTH, "mm"), scope);
                case MASS -> Expr.eval(t.matches("[-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?") ? t + "*kg"
                    : Inputs.normalize(t, Units.Category.MASS, "kg"), scope) / Units.KILOGRAM;
                case MIP_SIGNAL -> Expr.eval(Inputs.normalize(t, Units.Category.ENERGY, "MeV"), scope);
                default -> Expr.eval(t, scope);
            };
        }
    }

    public enum Op {
        AT_LEAST("≥"), AT_MOST("≤"), EQUAL("=");

        public final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }

        public static Op of(String s) {
            return switch (s.trim()) {
                case ">=", ">", "≥", "atleast", "min" -> AT_LEAST;
                case "<=", "<", "≤", "atmost", "max" -> AT_MOST;
                case "=", "==" -> EQUAL;
                default -> null;
            };
        }
    }

    /** A target for a metric. */
    public record Goal(Metric metric, Op op, double target) {
        public boolean met(double v) {
            if (Double.isNaN(v)) {
                return false;
            }
            final double eps = 1e-9 * Math.max(1, Math.abs(target));
            return switch (op) {
                case AT_LEAST -> v >= target - eps;
                case AT_MOST -> v <= target + eps;
                case EQUAL -> Math.abs(v - target) <= 1e-3 * Math.max(1e-6, Math.abs(target));
            };
        }

        /** "energy kept ≥ 95 %". */
        public String describe() {
            return metric.label + " " + op.symbol + " " + metric.format(target);
        }
    }

    /** A variable that moves the metric: its effect is the metric's change for one more (a count) or 5% more. */
    public record Lever(String variable, String expr, double value, boolean count, double effect) {
        public String describe(Metric m) {
            return String.format(Locale.ROOT, "%s (%s): %s%s per %s", variable, expr, effect >= 0 ? "+" : "",
                m.format(effect), count ? "one more" : "+5%");
        }
    }

    /**
     * The answer: the variable's expression before and after, the metric there and at the start. reached says the
     * target is met by after; already that it was met before.
     */
    public record Result(Goal goal, String variable, String before, String after, double start, double achieved,
                         boolean reached, boolean already, int trials, String message, boolean worldGrown) {
        public boolean changes() {
            return after != null && !after.equals(before);
        }

        /** The console command that applies it. */
        public String command() {
            return "var " + variable + " = " + after;
        }
    }

    // ------------------------------------------------------------------
    // Trying values on a copy
    // ------------------------------------------------------------------

    /**
     * A copy of the design to try values on: the document itself is never touched. A detector grown past the world
     * gets a world that holds it, as 'world fit' would give it, so that the world's size never limits the answer; a
     * gun with no position set stays just inside that world.
     */
    private static final class Trial {
        final Geometry g;
        final BeamPhysics.Beam beam;
        final Metric metric;
        final Set<String> knownErrors = new HashSet<>();
        final boolean defaultOrigin;
        final String worldX, worldY, worldZ;
        int trials;
        boolean grew;

        Trial(Geometry original, BeamPhysics.Beam beam, Metric metric) throws GeometryException {
            g = Gdml.read(Gdml.write(original), null).geometry();
            g.rebuild();
            for (Geometry.Issue i : g.issues()) {
                if (i.severity() == Geometry.Severity.ERROR) {
                    knownErrors.add(i.message());
                }
            }
            this.beam = beam;
            this.metric = metric;
            final String gp = original.settings().get("gun.position");
            defaultOrigin = gp == null || gp.isBlank();
            final Solid w = g.world() == null ? null : g.world().solid();
            final boolean box = w != null && "box".equals(w.tag());
            worldX = box ? w.param("x").expr() : null;
            worldY = box ? w.param("y").expr() : null;
            worldZ = box ? w.param("z").expr() : null;
        }

        /** The metric with a variable set so; NaN when the design does not hold together there. */
        double with(Define d, String expr) {
            d.setExpr(expr);
            return measure();
        }

        double measure() {
            if (worldX != null) {
                // each trial starts from the world as it was, grown again only if this value needs it
                final Solid w = g.world().solid();
                w.set("x", worldX);
                w.set("y", worldY);
                w.set("z", worldZ);
            }
            g.rebuild();
            trials++;
            grew = growWorld(g);
            for (Geometry.Issue i : g.issues()) {
                if (i.severity() == Geometry.Severity.ERROR && !knownErrors.contains(i.message())) {
                    return Double.NaN;
                }
            }
            final BeamPhysics.Beam b = defaultOrigin && grew ? beam.aimed(BeamPhysics.Beam.entry(g, beam.direction()),
                beam.direction()) : beam;
            return metric.of(BeamPhysics.analyze(g, b), g);
        }
    }

    /**
     * Something a goal turns: a variable of the design (G4editor), a constant of the code (G4code). Each value is
     * tried on a copy, never on the document.
     */
    public interface Knob {
        String name();

        /** As it is written now. */
        String text();

        /** Its value now, in internal units. */
        double value();

        /** A whole number of things (layers, cells): it moves by whole steps. */
        boolean count();

        /** A value written as the knob writes it: 12.5*mm, 26. */
        String express(double v);

        /** The metric with the knob written so; NaN when the design does not hold together there. */
        double measure(String text);

        /** The metric as the design is. */
        default double measure() {
            return measure(text());
        }

        /** True when the last measure had to grow the world to hold the design. */
        default boolean grew() {
            return false;
        }

        /** The designs measured so far. */
        int trials();
    }

    /** A variable of the design, turned on the copy a trial holds. */
    private static final class DefineKnob implements Knob {
        final Trial t;
        final Define d;
        final String before;
        final double v0;
        final boolean count;

        DefineKnob(Trial t, Define d) {
            this.t = t;
            this.d = d;
            this.before = d.expr();
            this.v0 = d.value();
            this.count = isCount(d);
        }

        public String name() {
            return d.name();
        }

        public String text() {
            return before;
        }

        public double value() {
            return v0;
        }

        public boolean count() {
            return count;
        }

        public String express(double v) {
            return GoalSeek.express(v, d, count);
        }

        public double measure(String text) {
            return t.with(d, text);
        }

        public boolean grew() {
            return t.grew;
        }

        public int trials() {
            return t.trials;
        }
    }

    /** A variable that can be driven: a plain value (with or without unit), not computed from other variables. */
    public static boolean isLever(Define d) {
        if (d == null || d.error() != null || d.kind() == Define.Kind.VARIABLE) {
            return false;
        }
        try {
            return Expr.parse(d.expr()).variables().isEmpty();
        } catch (GeometryException e) {
            return false;
        }
    }

    /** A count: a whole number, one or more, without unit (layers, cells, sectors). */
    public static boolean isCount(Define d) {
        try {
            final Expr e = Expr.parse(d.expr());
            if (!e.variables().isEmpty() || e.mentionsUnit() || d.kind() == Define.Kind.QUANTITY) {
                return false;
            }
        } catch (GeometryException e) {
            return false;
        }
        final double v = d.value();
        return v >= 1 && v == Math.rint(v);
    }

    private static final Pattern NUMBER_UNIT = Pattern.compile(
        "\\(?\\s*([-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?)\\s*\\)?\\s*\\*\\s*([A-Za-z_][A-Za-z0-9_]*)");

    /** A value written as the variable was: a count as a whole number, a length in the unit it was typed in. */
    static String express(double v, Define d, boolean count) {
        if (count) {
            return Long.toString(Math.round(v));
        }
        if (d.kind() == Define.Kind.QUANTITY && d.unit() != null && !d.unit().isBlank()) {
            return Units.num(v / unitValue(d.unit()));
        }
        final Matcher m = NUMBER_UNIT.matcher(d.expr().trim());
        if (m.matches()) {
            final double u = unitValue(m.group(2));
            if (u > 0) {
                return Units.num(v / u) + "*" + m.group(2);
            }
        }
        return Units.num(v);
    }

    private static double unitValue(String unit) {
        try {
            return Expr.eval("1*" + unit, Expr.NO_VARIABLES);
        } catch (GeometryException e) {
            return 1;
        }
    }

    /**
     * Grows a box world, centered on the origin as Geant4 expects, until it holds what it holds again with a margin
     * of a tenth of the content's size; never shrinks it. True when it had to.
     */
    public static boolean growWorld(Geometry g) {
        final LogicalVolume w = g.world();
        if (w == null || w.solid() == null || w.solid().error() != null || !"box".equals(w.solid().tag())) {
            return false;
        }
        BBox b = BBox.EMPTY;
        for (Geometry.Instance i : g.instances()) {
            if (i.pv() != null && i.depth() == 1) {
                b = b.union(Geometry.worldBox(i));
            }
        }
        final BBox e = w.solid().extent();
        if (b.isEmpty() || e.contains(b)) {
            return false;
        }
        final double m = 0.1 * b.diagonal();
        final double hx = Math.max(Math.max(Math.abs(b.minX()), Math.abs(b.maxX())) + m, e.maxX());
        final double hy = Math.max(Math.max(Math.abs(b.minY()), Math.abs(b.maxY())) + m, e.maxY());
        final double hz = Math.max(Math.max(Math.abs(b.minZ()), Math.abs(b.maxZ())) + m, e.maxZ());
        w.solid().set("x", Units.num(2 * hx) + "*mm");
        w.solid().set("y", Units.num(2 * hy) + "*mm");
        w.solid().set("z", Units.num(2 * hz) + "*mm");
        g.rebuild();
        return true;
    }

    /** The mass of everything the world holds, in internal units. */
    public static double detectorMass(Geometry g) {
        if (g.world() == null) {
            return 0;
        }
        double m = 0;
        for (PhysicalVolume pv : g.world().daughters()) {
            m += pv.copies() * Mass.of(g, pv.logical());
        }
        return m;
    }

    // ------------------------------------------------------------------
    // Levers: which variables move the metric
    // ------------------------------------------------------------------

    /** The variables that move the metric, the strongest first; each tried on a copy, 5% more or one more. */
    public static List<Lever> levers(Geometry g, BeamPhysics.Beam beam, Metric metric) throws GeometryException {
        final Trial t = new Trial(g, beam, metric);
        final double m0 = t.measure();
        final List<Lever> out = new ArrayList<>();
        if (Double.isNaN(m0)) {
            return out;
        }
        for (Define d : List.copyOf(t.g.defines())) {
            if (!isLever(d) || (d.value() == 0 && !isCount(d))) {
                continue;
            }
            final boolean count = isCount(d);
            final String old = d.expr();
            final double v = d.value();
            final double m = t.with(d, express(count ? v + 1 : v * 1.05, d, count));
            d.setExpr(old);
            if (!Double.isNaN(m) && Math.abs(m - m0) > 1e-9 * Math.max(1, Math.abs(m0))) {
                out.add(new Lever(d.name(), old, v, count, m - m0));
            }
        }
        out.sort(Comparator.comparingDouble((Lever l) -> -Math.abs(l.effect())));
        return out;
    }

    // ------------------------------------------------------------------
    // The search
    // ------------------------------------------------------------------

    /** The value of a variable that meets a goal, searched on a copy of the design. */
    public static Result solve(Geometry g, BeamPhysics.Beam beam, Goal goal, String variable) throws GeometryException {
        final Define original = g.define(variable);
        if (original == null) {
            throw new GeometryException("no variable named " + variable + "; 'vars' lists them");
        }
        if (!isLever(original)) {
            throw new GeometryException(variable + " = " + original.expr() + " is computed from other variables: drive one"
                + " of them");
        }
        final Trial t = new Trial(g, beam, goal.metric());
        return solve(new DefineKnob(t, t.g.define(variable)), goal, beam);
    }

    /** The effect of a knob on a metric: one more for a count, 5% more otherwise; null when it does not move it. */
    public static Lever lever(Knob k, double m0) {
        final double v = k.value();
        if (Double.isNaN(m0) || (v == 0 && !k.count())) {
            return null;
        }
        final double m = k.measure(k.express(k.count() ? v + 1 : v * 1.05));
        return Double.isNaN(m) || Math.abs(m - m0) <= 1e-9 * Math.max(1, Math.abs(m0)) ? null
            : new Lever(k.name(), k.text(), v, k.count(), m - m0);
    }

    /** The value of a knob that meets a goal for a beam, every value tried on the knob's copy. */
    public static Result solve(Knob k, Goal goal, BeamPhysics.Beam beam) throws GeometryException {
        final String variable = k.name();
        final String before = k.text();
        final boolean count = k.count();
        final double v0 = k.value();
        final double m0 = k.measure();
        if (Double.isNaN(m0)) {
            throw new GeometryException("the " + goal.metric().label + " cannot be measured for " + beam.describe()
                + (beam.particle().kind == BeamPhysics.Particle.Kind.MUON ? " (a muon makes no shower)" : ""));
        }
        final double target = goal.target();
        final boolean already = goal.met(m0);
        // the range the search may go over
        final double lo, hi;
        if (count) {
            lo = 1;
            hi = 1_000_000;
        } else if (v0 > 0) {
            lo = v0 * 1e-4;
            hi = v0 * 1e4;
        } else {
            final double span = 1e4 * Math.max(1, Math.abs(v0));
            lo = v0 - span;
            hi = v0 + span;
        }
        final double step0 = count ? 1 : v0 != 0 ? Math.abs(v0) * 0.05 : 1;
        // which way the metric goes
        double slope = 0;
        for (int sgn : new int[] {1, -1}) {
            final double v = clamp(v0 + sgn * step0, lo, hi);
            if (v == v0) {
                continue;
            }
            final double m = k.measure(k.express(v));
            if (!Double.isNaN(m) && Math.abs(m - m0) > 1e-12 * Math.max(1, Math.abs(m0))) {
                slope = (m - m0) / (v - v0);
                break;
            }
        }
        if (slope == 0) {
            return new Result(goal, variable, before, before, m0, m0, already, already, k.trials(), "Changing " + variable
                + " does not move the " + goal.metric().label + " (" + goal.metric().format(m0) + ")", false);
        }
        if (Math.abs(m0 - target) <= 1e-12 * Math.max(1, Math.abs(target))) {
            return new Result(goal, variable, before, before, m0, m0, true, true, k.trials(), "Met exactly as it is", false);
        }
        final int dir = (target - m0) * slope > 0 ? 1 : -1;
        // bracket the target by doubling steps
        double a = v0, fa = m0 - target, b = v0, fb = fa;
        double s = step0;
        boolean bracketed = false;
        for (int i = 0; i < 80; i++) {
            b = clamp(a + dir * s, lo, hi);
            if (count) {
                b = Math.rint(b);
            }
            if (b == a) {
                break;
            }
            fb = k.measure(k.express(b)) - target;
            if (Double.isNaN(fb)) {
                s /= 4;
                if (s < (count ? 1 : 1e-9 * Math.max(1, Math.abs(a)))) {
                    break;
                }
                continue;
            }
            if (fb == 0 || Math.signum(fb) != Math.signum(fa)) {
                bracketed = true;
                break;
            }
            a = b;
            fa = fb;
            s *= 2;
        }
        if (!bracketed) {
            final double reach = fa + target;
            final String at = k.express(a);
            return new Result(goal, variable, before, at, m0, reach, goal.met(reach), already, k.trials(),
                "Out of reach by " + variable + ": the " + goal.metric().label + " goes only to " + goal.metric().format(reach)
                    + " (" + variable + " = " + at + ")", false);
        }
        // halve the bracket
        for (int i = 0; i < 80; i++) {
            if (count ? Math.abs(b - a) <= 1 : Math.abs(b - a) <= 1e-6 * Math.max(Math.abs(a), Math.abs(b))) {
                break;
            }
            final double m = count ? Math.rint((a + b) / 2) : (a + b) / 2;
            if (m == a || m == b) {
                break;
            }
            final double fm = k.measure(k.express(m)) - target;
            if (Double.isNaN(fm)) {
                break;
            }
            if (fm == 0) {
                a = b = m;
                fa = fb = 0;
                break;
            }
            if (Math.signum(fm) == Math.signum(fa)) {
                a = m;
                fa = fm;
            } else {
                b = m;
                fb = fm;
            }
        }
        // the side that meets the goal (or the nearest, for an equality), written as a person would
        double v;
        if (goal.op() == Op.EQUAL) {
            v = count ? (Math.abs(fa) <= Math.abs(fb) ? a : b) : fa == fb ? a : a + (b - a) * fa / (fa - fb);
        } else {
            final boolean aMeets = goal.met(fa + target);
            v = aMeets ? a : b;
        }
        String after = k.express(v);
        if (!count) {
            final String round = k.express(round4(v, goal, slope));
            final double mr = k.measure(round);
            if (goal.op() == Op.EQUAL ? Math.abs(mr - target) <= Math.abs(k.measure(after) - target) + 1e-3 * Math.abs(target)
                : goal.met(mr)) {
                after = round;
            }
        }
        final double achieved = k.measure(after);
        final boolean grown = k.grew();
        final boolean reached = goal.met(achieved) || (goal.op() == Op.EQUAL
            && Math.abs(achieved - target) <= 0.01 * Math.max(1e-6, Math.abs(target)));
        final String msg;
        if (already) {
            msg = String.format(Locale.ROOT, "Already met: %s is %s. The boundary is %s = %s (%s there; %s now)",
                goal.metric().label, goal.metric().format(m0), variable, after, goal.metric().format(achieved), before);
        } else {
            msg = String.format(Locale.ROOT, "%s %s → %s: %s %s (was %s)", variable, before, after,
                goal.metric().label, goal.metric().format(achieved), goal.metric().format(m0));
        }
        return new Result(goal, variable, before, after, m0, achieved, reached, already, k.trials(),
            msg + (grown ? "; the world grows to hold it" : ""), grown);
    }

    /** Four significant digits, rounded the way that keeps an inequality met (the metric's slope says which way). */
    private static double round4(double v, Goal goal, double slope) {
        if (v == 0) {
            return 0;
        }
        final double mag = Math.pow(10, Math.floor(Math.log10(Math.abs(v))) - 3);
        if (goal.op() == Op.EQUAL) {
            return Math.rint(v / mag) * mag;
        }
        // AT_LEAST with a rising metric wants the value no smaller: round up; and so on
        final boolean up = (goal.op() == Op.AT_LEAST) == (slope > 0);
        return (up ? Math.ceil(v / mag - 1e-9) : Math.floor(v / mag + 1e-9)) * mag;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Reads "containment >= 95% by cal_nLayers", "x0 = 25 by cal_absT", "mass <= 2 t": the goal, and the variable
     * named after "by" (null when none is).
     */
    public static Object[] parse(String text, Expr.Scope scope) throws GeometryException {
        final Matcher m = Pattern.compile("\\s*([A-Za-z][A-Za-z0-9_\\-]*(?:\\s+fraction)?)\\s*(>=|<=|==|=|>|<|≥|≤)\\s*(.+?)"
            + "(?:\\s+by\\s+([A-Za-z_][A-Za-z0-9_]*))?\\s*$").matcher(text);
        if (!m.matches()) {
            throw new GeometryException("goal <metric> >= <value> [by <variable>]   metrics: containment, longitudinal,"
                + " lateral, x0, lambda, depth, sampling, resolution, signal, mass");
        }
        final Metric metric = Metric.of(m.group(1).replace(" ", ""));
        if (metric == null) {
            throw new GeometryException("unknown metric " + m.group(1) + "; one of containment, longitudinal, lateral, x0,"
                + " lambda, depth, sampling, resolution, signal, mass");
        }
        final Op op = Op.of(m.group(2));
        return new Object[] {new Goal(metric, op, metric.parse(m.group(3), scope)), m.group(4)};
    }
}
