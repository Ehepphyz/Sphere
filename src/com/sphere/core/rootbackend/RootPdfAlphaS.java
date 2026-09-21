package com.sphere.core.rootbackend;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The strong coupling, as the set that carries it defines it.
 *
 * A cross section is a power of alpha_s, so the number used has to be the one
 * the set was fitted with. A set says in its info file which of three ways its
 * alpha_s is computed: an analytic expansion in one over the logarithm, a table
 * of values to interpolate, or a numerical solution of the renormalization
 * group equation. They do not agree with one another, and picking the wrong one
 * is a percent-level error in a quantity that appears squared or cubed.
 *
 * All three are here, following LHAPDF's own code, so that a set read by Sphere
 * gives the coupling LHAPDF would have given it. The flavor count changes with
 * the scale, which is what the thresholds are for, and the beta coefficients
 * are functions of that count rather than constants.
 */
public final class RootPdfAlphaS {

    /** How the coupling is computed. */
    public enum Type { ANALYTIC, IPOL, ODE }

    /** Whether the flavor count follows the scale or is held. */
    public enum Flavors { VARIABLE, FIXED }

    /** The step the solver takes in the logarithm of the scale squared. */
    private static final double LOG_STEP = 0.001;

    /** What a divergent coupling answers, as LHAPDF does. */
    public static final double DIVERGENT = Double.MAX_VALUE;

    private Type type = Type.IPOL;
    private int order = 5;
    private double mz = 91.1876;
    private double alphaSmz = 0.118;
    private Flavors scheme = Flavors.VARIABLE;
    private int fixedFlavors = -1;

    private double massReference = 0.0;
    private double alphaSreference = 0.0;
    private boolean customReference = false;

    private final Map<Integer, Double> quarkMasses = new TreeMap<>();
    private final Map<Integer, Double> thresholds = new TreeMap<>();
    private final Map<Integer, Double> lambdas = new TreeMap<>();
    private int nfMin = 0;
    private int nfMax = 6;

    /** The table the interpolated form reads, and the one the solver fills. */
    private double[] q2s = new double[0];
    private double[] values = new double[0];

    /** One stretch of the table between two thresholds. */
    private record Piece(double[] q2s, double[] logq2s, double[] as) { }

    private TreeMap<Double, Piece> pieces;
    private boolean solved = false;

    /**
     * Whether to answer exactly what LHAPDF would, including where it is wrong.
     *
     * Two places differ. LHAPDF holds the coupling constant above the last
     * scale in its table, and when the set names no scales its own default
     * table stops at 1950 GeV, which is inside the range an LHC analysis uses.
     * Set this and Sphere does the same, which is what reproducing a published
     * number needs. Leave it and the table reaches 100 TeV and the running
     * continues above it.
     */
    private boolean matchLhapdf = false;

    /**
     * How far the solver tabulates when the set names no scales.
     *
     * A hundred TeV covers the LHC and a hadron collider after it. Past the end
     * of the table the running is continued from its last two knots, which
     * costs a tenth of a percent at twice the ceiling and rather more further
     * out, so a study that lives up there raises this instead.
     */
    private double tableCeiling = 100000.0;

    private RootPdfAlphaS() { }

    /* ------------------------------------------------------------------ */
    /* Reading it out of a set                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Builds the coupling a set's info file describes.
     *
     * Returns null when the file says nothing about alpha_s, which is normal
     * for a grid written by hand and for the older sets. A caller that needs
     * the coupling anyway can build one itself from a reference value.
     */
    public static RootPdfAlphaS fromInfo(Map<String, String> info) {
        if (info == null) {
            return null;
        }
        final String said = text(info, "AlphaS_Type", "");
        if (said.isEmpty()) {
            return null;
        }
        RootPdfAlphaS as = new RootPdfAlphaS();
        switch (said.toLowerCase(Locale.ROOT)) {
            case "analytic" -> as.type = Type.ANALYTIC;
            case "ode" -> as.type = Type.ODE;
            case "ipol" -> as.type = Type.IPOL;
            default -> {
                return null;
            }
        }
        as.order = (int) number(info, "AlphaS_OrderQCD", 4);

        // Thresholds are where the flavor count changes. They are the quark
        // masses unless the set says otherwise, and a set that moves them has
        // to give all six or none: a half-filled set of thresholds would put
        // the transition in a different place for different quarks.
        final String[] named = {"Down", "Up", "Strange", "Charm", "Bottom", "Top"};
        if (allPresent(info, "AlphaS_Threshold", named)) {
            for (int q = 1; q <= 6; q++) {
                as.thresholds.put(q, number(info, "AlphaS_Threshold" + named[q - 1], 0));
            }
        } else if (allPresent(info, "Threshold", named)) {
            for (int q = 1; q <= 6; q++) {
                as.thresholds.put(q, number(info, "Threshold" + named[q - 1], 0));
            }
        }
        if (allPresent(info, "AlphaS_M", named)) {
            for (int q = 1; q <= 6; q++) {
                as.quarkMasses.put(q, number(info, "AlphaS_M" + named[q - 1], 0));
            }
        } else if (allPresent(info, "M", named)) {
            for (int q = 1; q <= 6; q++) {
                as.quarkMasses.put(q, number(info, "M" + named[q - 1], 0));
            }
        }

        final String fscheme = text(info, "AlphaS_FlavorScheme",
                                    text(info, "FlavorScheme", "variable"));
        final int count = (int) number(info, "AlphaS_NumFlavors",
                                       number(info, "NumFlavors", 5));
        if (fscheme.equalsIgnoreCase("fixed")) {
            as.scheme = Flavors.FIXED;
            as.fixedFlavors = count;
        } else {
            as.scheme = Flavors.VARIABLE;
            as.fixedFlavors = count;
        }

        as.mz = number(info, "MZ", 91.1876);
        as.alphaSmz = number(info, "AlphaS_MZ", 0.118);
        if (info.containsKey("AlphaS_Reference") && info.containsKey("AlphaS_MassReference")) {
            as.alphaSreference = number(info, "AlphaS_Reference", 0);
            as.massReference = number(info, "AlphaS_MassReference", 0);
            as.customReference = true;
        }

        switch (as.type) {
            case ANALYTIC -> {
                for (int nf = 3; nf <= 5; nf++) {
                    if (info.containsKey("AlphaS_Lambda" + nf)) {
                        as.lambdas.put(nf, number(info, "AlphaS_Lambda" + nf, 0));
                    }
                }
                if (as.lambdas.isEmpty()) {
                    return null;
                }
                as.countFlavors();
            }
            case IPOL -> {
                final double[] qs = list(info, "AlphaS_Qs");
                final double[] vals = list(info, "AlphaS_Vals");
                if (qs.length == 0 || qs.length != vals.length) {
                    return null;
                }
                as.q2s = squared(qs);
                as.values = vals;
            }
            case ODE -> {
                final double[] qs = list(info, "AlphaS_Qs");
                if (qs.length > 0) {
                    as.q2s = squared(qs);
                }
                if (as.quarkMasses.isEmpty()) {
                    return null;
                }
            }
            default -> { }
        }
        return as;
    }

    /** A coupling built by hand, for a grid whose info file says nothing. */
    public static RootPdfAlphaS analytic(double lambda5, int order) {
        RootPdfAlphaS as = new RootPdfAlphaS();
        as.type = Type.ANALYTIC;
        as.order = order;
        as.lambdas.put(5, lambda5);
        as.quarkMasses.put(4, 1.3);
        as.quarkMasses.put(5, 4.5);
        as.quarkMasses.put(6, 175.0);
        as.countFlavors();
        return as;
    }

    /* ------------------------------------------------------------------ */
    /* The value                                                           */
    /* ------------------------------------------------------------------ */

    /** The coupling at a scale squared. */
    public double alphasQ2(double q2) {
        if (q2 < 0.0) {
            return Double.NaN;
        }
        return switch (type) {
            case ANALYTIC -> analyticAt(q2);
            case IPOL -> interpolatedAt(q2);
            case ODE -> odeAt(q2);
        };
    }

    /** The coupling at a scale. */
    public double alphasQ(double q) {
        return alphasQ2(q * q);
    }

    /**
     * How many quark flavors are active at a scale.
     *
     * A quark counts once the scale passes its threshold, which is its mass
     * unless the set moved it. Below the charm threshold three flavors run,
     * above the bottom one five, and the beta coefficients differ accordingly:
     * this is not a detail, it is why the coupling bends at those scales.
     */
    public int numFlavorsQ2(double q2) {
        if (scheme == Flavors.FIXED) {
            return fixedFlavors;
        }
        final Map<Integer, Double> where = thresholds.isEmpty() ? quarkMasses : thresholds;
        final int from = type == Type.ANALYTIC ? nfMin : 1;
        final int to = type == Type.ANALYTIC ? nfMax : 6;
        int nf = type == Type.ANALYTIC ? nfMin : 0;
        for (int q = from; q <= to; q++) {
            final Double at = where.get(q);
            if (at != null && at * at < q2) {
                nf = q;
            }
        }
        if (fixedFlavors != -1 && nf > fixedFlavors) {
            nf = fixedFlavors;
        }
        return nf;
    }

    /**
     * One beta coefficient, for a given flavor count.
     *
     * The decimals are LHAPDF's, kept rather than recomputed from the exact
     * fractions: a coupling that differs in the eighth digit from the library
     * everyone compares against is a difference nobody wants to explain.
     */
    public double beta(int i, int nf) {
        return switch (i) {
            case 0 -> 0.875352187 - 0.053051647 * nf;
            case 1 -> 0.6459225457 - 0.0802126037 * nf;
            case 2 -> 0.719864327 - 0.140904490 * nf + 0.00303291339 * nf * nf;
            case 3 -> 1.172686 - 0.2785458 * nf + 0.01624467 * nf * nf
                      + 0.0000601247 * nf * nf * nf;
            case 4 -> 1.714138 - 0.5940794 * nf + 0.05607482 * nf * nf
                      - 0.0007380571 * nf * nf * nf - 0.00000587968 * nf * nf * nf * nf;
            default -> throw new IllegalArgumentException("No beta coefficient " + i);
        };
    }

    private double[] betas(int nf) {
        double[] out = new double[5];
        for (int i = 0; i < 5; i++) {
            out[i] = beta(i, nf);
        }
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* Analytic                                                            */
    /* ------------------------------------------------------------------ */

    private void countFlavors() {
        nfMin = 0;
        nfMax = 6;
        for (int q = 0; q <= 6; q++) {
            if (lambdas.containsKey(q)) {
                nfMin = q;
                break;
            }
        }
        for (int q = 6; q >= 0; q--) {
            if (lambdas.containsKey(q)) {
                nfMax = q;
                break;
            }
        }
    }

    /**
     * The scale parameter for a flavor count.
     *
     * A set usually gives Lambda for four and five flavors and nothing below,
     * so a query in the three-flavor range falls back to the next one defined.
     */
    private double lambdaQCD(int nf) {
        if (scheme == Flavors.FIXED) {
            final Double one = lambdas.get(fixedFlavors);
            if (one == null) {
                throw new IllegalStateException(
                    "A fixed " + fixedFlavors + " flavor scheme needs Lambda(" + fixedFlavors + ").");
            }
            return one;
        }
        for (int q = nf; q >= 0; q--) {
            final Double one = lambdas.get(q);
            if (one != null) {
                return one;
            }
        }
        throw new IllegalStateException("No Lambda is defined at or below " + nf + " flavors.");
    }

    private double analyticAt(double q2) {
        if (lambdas.isEmpty()) {
            return Double.NaN;
        }
        final int nf = numFlavorsQ2(q2);
        final double lambda = lambdaQCD(nf);
        if (q2 <= lambda * lambda) {
            return DIVERGENT;
        }
        if (order == 0) {
            return alphaSmz;
        }

        final double[] b = betas(nf);
        final double b02 = b[0] * b[0];
        final double b12 = b[1] * b[1];

        final double lnx = Math.log(q2 / (lambda * lambda));
        final double lnlnx = Math.log(lnx);
        final double lnlnx2 = lnlnx * lnlnx;
        final double lnlnx3 = lnlnx2 * lnlnx;
        final double y = 1.0 / lnx;

        double sum = 1.0;
        if (order > 1) {
            sum -= (b[1] * lnlnx / b02) * y;
        }
        if (order > 2) {
            final double bb = b12 / (b02 * b02);
            sum += bb * y * y * (lnlnx2 - lnlnx + b[2] * b[0] / b12 - 1.0);
        }
        if (order > 3) {
            final double cc = 1.0 / (b02 * b02 * b02);
            final double a30 = (b12 * b[1])
                * (lnlnx3 - 2.5 * lnlnx2 - 2.0 * lnlnx + 0.5);
            final double a31 = 3.0 * b[0] * b[1] * b[2] * lnlnx;
            final double a32 = 0.5 * b02 * b[3];
            sum -= cc * y * y * y * (a30 + a31 - a32);
        }
        return y * sum / b[0];
    }

    /* ------------------------------------------------------------------ */
    /* Interpolated                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * Splits the table wherever a scale is repeated.
     *
     * A repeated Q is a threshold: the coupling is allowed to step there, and
     * interpolating across the step would smear a discontinuity that belongs in
     * the answer. Each stretch between two of them is interpolated on its own.
     */
    private void buildPieces() {
        pieces = new TreeMap<>();
        List<Double> q = new ArrayList<>();
        List<Double> a = new ArrayList<>();
        double previous = q2s.length == 0 ? 0.0 : q2s[0];

        for (int i = 0; i <= q2s.length; i++) {
            final double here = (i != q2s.length) ? q2s[i] : q2s[q2s.length - 1];
            final double value = (i != q2s.length) ? values[i] : -1.0;
            if (Math.abs(here - previous) < Math.ulp(1.0)) {
                if (i != 0 && !q.isEmpty()) {
                    pieces.put(q.get(0), piece(q, a));
                }
                q = new ArrayList<>();
                a = new ArrayList<>();
            }
            q.add(here);
            a.add(value);
            previous = here;
        }
    }

    private static Piece piece(List<Double> q, List<Double> a) {
        double[] qq = new double[q.size()];
        double[] lq = new double[q.size()];
        double[] aa = new double[a.size()];
        for (int i = 0; i < qq.length; i++) {
            qq[i] = q.get(i);
            lq[i] = Math.log(qq[i]);
            aa[i] = a.get(i);
        }
        return new Piece(qq, lq, aa);
    }

    private double interpolatedAt(double q2) {
        if (q2s.length == 0) {
            return Double.NaN;
        }
        // Below the table the slope in the logs is continued, which keeps the
        // line straight on the plot everyone reads this on. Above it the last
        // value is held, because nothing in the table says how it would run.
        if (q2 < q2s[0]) {
            int next = 1;
            while (next < q2s.length && q2s[0] == q2s[next]) {
                next++;
            }
            if (next >= q2s.length || values[0] <= 0.0 || values[next] <= 0.0) {
                return values[0];
            }
            final double slope = Math.log10(values[next] / values[0])
                               / Math.log10(q2s[next] / q2s[0]);
            return values[0] * Math.pow(q2 / q2s[0], slope);
        }
        if (q2 > q2s[q2s.length - 1]) {
            if (matchLhapdf || q2s.length < 3) {
                return values[values.length - 1];
            }
            // The table ends, the coupling does not. Continuing the slope of
            // the last two knots in the logs is what the equation does there,
            // and holding the last value instead is wrong by a percent per
            // factor of two in the scale.
            final int n = q2s.length;
            final double a = values[n - 2];
            final double b = values[n - 1];
            if (a <= 0.0 || b <= 0.0 || q2s[n - 1] == q2s[n - 2]) {
                return b;
            }
            final double slope = Math.log(b / a) / Math.log(q2s[n - 1] / q2s[n - 2]);
            return b * Math.pow(q2 / q2s[n - 1], slope);
        }
        if (pieces == null) {
            buildPieces();
        }
        final Map.Entry<Double, Piece> found = pieces.floorEntry(q2);
        if (found == null) {
            return values[0];
        }
        final Piece one = found.getValue();
        final int i = below(q2, one.q2s());

        final double slopeLow;
        final double slopeHigh;
        if (i == 0) {
            slopeLow = forward(one, i);
            slopeHigh = central(one, i + 1);
        } else if (i == one.logq2s().length - 2) {
            slopeLow = central(one, i);
            slopeHigh = backward(one, i + 1);
        } else {
            slopeLow = central(one, i);
            slopeHigh = central(one, i + 1);
        }

        final double dlog = one.logq2s()[i + 1] - one.logq2s()[i];
        final double t = (Math.log(q2) - one.logq2s()[i]) / dlog;
        return cubic(t, one.as()[i], slopeLow * dlog, one.as()[i + 1], slopeHigh * dlog);
    }

    private static double forward(Piece p, int i) {
        return (p.as()[i + 1] - p.as()[i]) / (p.logq2s()[i + 1] - p.logq2s()[i]);
    }

    private static double backward(Piece p, int i) {
        return (p.as()[i] - p.as()[i - 1]) / (p.logq2s()[i] - p.logq2s()[i - 1]);
    }

    private static double central(Piece p, int i) {
        return 0.5 * (forward(p, i) + backward(p, i));
    }

    /** A coupling above two is not a coupling, so it is reported as divergent. */
    private static double cubic(double t, double vl, double vdl, double vh, double vdh) {
        final double t2 = t * t;
        final double t3 = t2 * t;
        final double out = (2.0 * t3 - 3.0 * t2 + 1.0) * vl
                         + (t3 - 2.0 * t2 + t) * vdl
                         + (-2.0 * t3 + 3.0 * t2) * vh
                         + (t3 - t2) * vdh;
        return Math.abs(out) < 2.0 ? out : DIVERGENT;
    }

    private static int below(double value, double[] knots) {
        int at = 0;
        int high = knots.length;
        while (at < high) {
            final int mid = (at + high) >>> 1;
            if (knots[mid] <= value) {
                at = mid + 1;
            } else {
                high = mid;
            }
        }
        if (at >= knots.length) {
            at = knots.length - 1;
        }
        return at == 0 ? 0 : at - 1;
    }

    /* ------------------------------------------------------------------ */
    /* Solved                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * How fast the coupling runs, at the order asked for.
     *
     * This is the renormalization group equation itself, written for the
     * solver: each order adds one power of the coupling to the right side.
     */
    private double derivative(double t, double y, double[] b) {
        if (order == 0) {
            return 0.0;
        }
        double d = b[0] * y * y;
        if (order == 1) {
            return -d / t;
        }
        d += b[1] * y * y * y;
        if (order == 2) {
            return -d / t;
        }
        d += b[2] * y * y * y * y;
        if (order == 3) {
            return -d / t;
        }
        d += b[3] * y * y * y * y * y;
        if (order == 4) {
            return -d / t;
        }
        d += b[4] * y * y * y * y * y * y;
        return -d / t;
    }

    /**
     * The step across a threshold.
     *
     * The coupling is not continuous where a quark enters: the theory on one
     * side has one flavor more than on the other, and the two are matched by
     * this series rather than joined. Ignoring it is a visible error in the
     * coupling above the bottom mass.
     */
    private double decouple(double y, double t, int from, int to) {
        if (from == to || order == 0) {
            return 1.0;
        }
        final double as = y / Math.PI;
        final int heavy = Math.max(from, to);
        final Double mass = quarkMasses.get(heavy);
        if (mass == null) {
            throw new IllegalStateException(
                "The quark masses are needed to step across a threshold.");
        }
        final double l = Math.log(t / (mass * mass));
        final double l2 = l * l;
        final double l3 = l2 * l;
        final double l4 = l3 * l;
        double a1;
        double a2;
        double a3;
        double a4;
        if (from > to) {
            a1 = -0.166666 * l * as;
            a2 = (0.152778 - 0.458333 * l + 0.0277778 * l2) * as * as;
            a3 = (0.972057 - 0.0846515 * to + (-1.65799 + 0.116319 * to) * l
                  + (0.0920139 - 0.0277778 * to) * l2 - 0.00462963 * l3) * as * as * as;
            a4 = (5.17035 - 1.00993 * to - 0.0219784 * to * to
                  + (-8.42914 + 1.30983 * to + 0.0367852 * to * to) * l
                  + (0.629919 - 0.143036 * to + 0.00371335 * to * to) * l2
                  + (-0.181617 - 0.0244985 * to + 0.00308642 * to * to) * l3
                  + 0.000771605 * l4) * as * as * as * as;
        } else {
            a1 = 0.166667 * l * as;
            a2 = (-0.152778 + 0.458333 * l + 0.0277778 * l2) * as * as;
            a3 = (-0.972057 + 0.0846515 * from + (1.53067 - 0.116319 * from) * l
                  + (0.289931 + 0.0277778 * from) * l2 + 0.00462963 * l3) * as * as * as;
            a4 = (-5.10032 + 1.00993 * from + 0.0219784 * from * from
                  + (7.03696 - 1.22518 * from - 0.0367852 * from * from) * l
                  + (1.59462 + 0.0267168 * from + 0.00371335 * from * from) * l2
                  + (0.280575 + 0.0522762 * from - 0.00308642 * from * from) * l3
                  + 0.000771605 * l4) * as * as * as * as;
        }
        double out = 1.0 + a1;
        if (order == 1) {
            return out;
        }
        out += a2;
        if (order == 2) {
            return out;
        }
        out += a3;
        if (order == 3) {
            return out;
        }
        return out + a4;
    }

    /** Where the solver's state lives while it walks from one scale to another. */
    private static final class Walk {
        double t;
        double y;
        double h;
    }

    /**
     * One Runge-Kutta step, halved until the change is small enough.
     *
     * LHAPDF recurses here without a floor. A coupling heading for its own
     * divergence makes the change grow faster than halving the step shrinks it,
     * and the recursion then ends the process rather than the integration. The
     * depth is bounded here instead: at that point the step is a thousand
     * billion times smaller than it started and the answer is not going to
     * improve, so it is taken.
     */
    private void step(Walk w, double allowed, double[] b, int depth) {
        final double k1 = w.h * derivative(w.t, w.y, b);
        final double k2 = w.h * derivative(w.t + w.h / 2.0, w.y + k1 / 2.0, b);
        final double k3 = w.h * derivative(w.t + w.h / 2.0, w.y + k2 / 2.0, b);
        final double k4 = w.h * derivative(w.t + w.h, w.y + k3, b);
        final double change = (k1 + 2.0 * k2 + 2.0 * k3 + k4) / 6.0;

        if (w.t > 1.0 && Math.abs(change) > allowed && depth < 40) {
            final double keep = w.h;
            w.h = w.h / 2.0;
            step(w, allowed, b, depth + 1);
            w.h = keep;
            return;
        }
        w.y += change;
        w.t += w.h;
    }

    /**
     * How fast the coupling runs, per unit of log of the scale squared.
     *
     * The same equation as the one above, in the variable it is natural in. A
     * step of fixed size in the logarithm covers the whole range a collider
     * uses in a few thousand steps; a step of fixed size in the scale squared
     * needs eighty million of them to reach thirteen TeV, which is why the
     * walk below was stopping short of it.
     */
    private double running(double y, double[] b) {
        if (order == 0) {
            return 0.0;
        }
        double d = b[0] * y * y;
        if (order == 1) {
            return -d;
        }
        d += b[1] * y * y * y;
        if (order == 2) {
            return -d;
        }
        d += b[2] * y * y * y * y;
        if (order == 3) {
            return -d;
        }
        d += b[3] * y * y * y * y * y;
        if (order == 4) {
            return -d;
        }
        d += b[4] * y * y * y * y * y * y;
        return -d;
    }

    /**
     * Integrates in the logarithm of the scale squared, at a fixed step.
     *
     * The flavor count is read at each step rather than once, because a walk
     * that crosses a threshold has a different equation on each side of it.
     */
    private void solveInLog(double q2, Walk w) {
        if (q2 <= 0.0 || w.t <= 0.0 || q2 == w.t) {
            return;
        }
        final double from = Math.log(w.t);
        final double to = Math.log(q2);
        final int steps = Math.max(64, (int) Math.ceil(Math.abs(to - from) / LOG_STEP));
        final double h = (to - from) / steps;
        double u = from;
        double y = w.y;
        for (int n = 0; n < steps; n++) {
            final double[] b = betas(numFlavorsQ2(Math.exp(u + 0.5 * h)));
            final double k1 = h * running(y, b);
            final double k2 = h * running(y + k1 / 2.0, b);
            final double k3 = h * running(y + k2 / 2.0, b);
            final double k4 = h * running(y + k3, b);
            y += (k1 + 2.0 * k2 + 2.0 * k3 + k4) / 6.0;
            u += h;
            if (!(y > 0.0) || y > 2.0) {
                w.t = Math.exp(u);
                w.y = DIVERGENT;
                return;
            }
        }
        w.t = q2;
        w.y = y;
    }

    private void solve(double q2, Walk w, double allowed, double accuracy) {
        if (q2 == w.t) {
            return;
        }
        if (!matchLhapdf) {
            solveInLog(q2, w);
            return;
        }
        int guard = 0;
        while (Math.abs(q2 - w.t) > accuracy && guard++ < 2_000_000) {
            if (Math.abs(w.h) > accuracy && Math.abs(q2 - w.t) / w.h < 10.0 && w.t > 1.0) {
                w.h = accuracy / 2.1;
            }
            if (Math.abs(w.h) > 0.01 && w.t < 1.0) {
                accuracy = 0.0051;
                w.h = 0.01;
            }
            if ((q2 < w.t && w.h > 0) || (q2 > w.t && w.h < 0)) {
                w.h *= -1.0;
            }
            step(w, allowed, betas(numFlavorsQ2(w.t)), 0);
            if (w.y > 2.0) {
                w.y = DIVERGENT;
                return;
            }
        }
    }

    private double odeAt(double q2) {
        if (!solved) {
            tabulate();
        }
        return interpolatedAt(q2);
    }

    /**
     * Solves once over a table of scales, then interpolates it.
     *
     * Integrating from the reference scale on every call would cost more than
     * the rest of an analysis, so the equation is solved at a set of scales and
     * read off between them. The walk goes down from the reference first, then
     * back up, so no stretch is integrated twice.
     */
    private void tabulate() {
        solved = true;
        if (q2s.length == 0) {
            q2s = defaultScales();
        }
        final double reference = customReference ? massReference * massReference : mz * mz;
        final double start = customReference ? alphaSreference : alphaSmz;
        if (q2s[q2s.length - 1] < mz * mz) {
            double[] longer = new double[q2s.length + 1];
            System.arraycopy(q2s, 0, longer, 0, q2s.length);
            longer[q2s.length] = mz * mz;
            q2s = longer;
        }

        int belowReference = 0;
        while (belowReference + 1 < q2s.length && q2s[belowReference + 1] < mz * mz) {
            belowReference++;
        }

        double[] out = new double[q2s.length];
        final double allowed = 0.01;
        final double accuracy = 0.001;

        Walk w = new Walk();
        w.t = reference;
        w.y = start;
        w.h = 2.0;
        double diverged = 0.0;
        double last = -1.0;
        boolean afterThreshold = false;

        for (int at = belowReference; at >= 0; at--) {
            final double q2 = q2s[at];
            if (at > 1 && q2 == q2s[at - 1]) {
                last = q2;
                afterThreshold = true;
                w.h = 2.0 / 5.0;
                solve(q2, w, allowed / 5.0, accuracy / 5.0);
                out[at] = w.y;
                w.y = w.y * decouple(w.y, w.t, numFlavorsQ2(q2s[at + 1]),
                                     numFlavorsQ2(q2s[at - 2]));
                if (w.y > 2.0) {
                    diverged = q2;
                }
                continue;
            }
            if (q2 < diverged) {
                out[at] = DIVERGENT;
                continue;
            }
            if (q2 == last) {
                out[at] = w.y;
                continue;
            }
            last = q2;
            w.h = afterThreshold ? 2.0 / 5.0 : 2.0;
            solve(q2, w, afterThreshold ? allowed / 5.0 : allowed,
                  afterThreshold ? accuracy / 5.0 : accuracy);
            afterThreshold = false;
            out[at] = w.y;
            if (w.y > 2.0) {
                diverged = q2;
            }
        }

        w = new Walk();
        w.t = reference;
        w.y = start;
        w.h = 2.0;
        for (int at = belowReference + 1; at < q2s.length; at++) {
            final double q2 = q2s[at];
            if (at < q2s.length - 2 && q2 == q2s[at + 1]) {
                last = q2;
                w.h = 2.0 / 5.0;
                solve(q2, w, allowed / 5.0, accuracy / 5.0);
                out[at] = w.y;
                w.y = w.y * decouple(w.y, w.t, numFlavorsQ2(q2s[at - 1]),
                                     numFlavorsQ2(q2s[at + 2]));
                if (w.y > 2.0) {
                    diverged = q2;
                }
                continue;
            }
            if (q2 < diverged) {
                out[at] = DIVERGENT;
                continue;
            }
            if (q2 == last) {
                out[at] = w.y;
                continue;
            }
            last = q2;
            w.h = 2.0;
            solve(q2, w, allowed, accuracy);
            out[at] = w.y;
            if (w.y > 2.0) {
                diverged = q2;
            }
        }

        values = out;
        pieces = null;
    }

    /**
     * The scales the solver uses when the set names none.
     *
     * LHAPDF's own default stops at 1950 GeV and holds the coupling constant
     * above it, which is inside the range an LHC analysis uses every day. The
     * table is carried to 100 TeV here instead. Below that last LHAPDF knot
     * the two agree; above it, LHAPDF answers a constant and this answers the
     * equation it was asked to solve.
     */
    private double[] defaultScales() {
        List<Double> out = new ArrayList<>();
        for (int q = 1; q / 10.0 < 1.0; q++) {
            out.add((q / 10.0) * (q / 10.0));
        }
        for (int q = 4; q / 4.0 < mz; q++) {
            out.add((q / 4.0) * (q / 4.0));
        }
        for (int q = (int) Math.ceil(mz / 4.0); 4 * q < 1000; q++) {
            out.add((double) (4 * q) * (4 * q));
        }
        for (int q = 1000 / 50; 50 * q < 2000; q++) {
            out.add((double) (50 * q) * (50 * q));
        }
        if (!matchLhapdf) {
            for (double q = 2000.0; q < tableCeiling; q *= 1.1) {
                out.add(q * q);
            }
            out.add(tableCeiling * tableCeiling);
        }
        final Map<Integer, Double> where = thresholds.isEmpty() ? quarkMasses : thresholds;
        for (int q = 4; q <= 6; q++) {
            final Double at = where.get(q);
            if (at != null) {
                out.add(at * at);
                out.add(at * at);
            }
        }
        double[] scales = new double[out.size()];
        for (int i = 0; i < scales.length; i++) {
            scales[i] = out.get(i);
        }
        java.util.Arrays.sort(scales);
        return scales;
    }

    /* ------------------------------------------------------------------ */
    /* What it is                                                          */
    /* ------------------------------------------------------------------ */

    /** Answer exactly what LHAPDF would, including where it is wrong. */
    public RootPdfAlphaS matchingLhapdf(boolean yes) {
        matchLhapdf = yes;
        solved = false;
        pieces = null;
        return this;
    }

    public boolean matchesLhapdf() { return matchLhapdf; }

    /**
     * Tabulates out to this scale instead of a hundred TeV.
     *
     * Only for a set that names no scales of its own: one that carries
     * AlphaS_Qs is tabulated where it says, and raising this would not change
     * where its table ends.
     */
    public RootPdfAlphaS tableTo(double maxQ) {
        if (maxQ > 2000.0) {
            tableCeiling = maxQ;
            solved = false;
            pieces = null;
        }
        return this;
    }

    /** How far the solver tabulates when the set names no scales. */
    public double tableCeiling() { return tableCeiling; }

    /**
     * The scale above which LHAPDF would hold the coupling constant.
     *
     * Worth knowing before trusting a number at a high scale: above this,
     * LHAPDF answers its last tabulated value whatever the scale.
     */
    public double frozenAbove() {
        if (type == Type.ODE && !solved) {
            tabulate();
        }
        return q2s.length == 0 ? Double.NaN : Math.sqrt(q2s[q2s.length - 1]);
    }

    public Type type() { return type; }
    public int orderQCD() { return order; }
    public double mZ() { return mz; }
    public double alphaSmZ() { return alphaSmz; }
    public Flavors flavorScheme() { return scheme; }
    public Map<Integer, Double> quarkMasses() { return new TreeMap<>(quarkMasses); }
    public Map<Integer, Double> quarkThresholds() { return new TreeMap<>(thresholds); }
    public Map<Integer, Double> lambdas() { return new TreeMap<>(lambdas); }

    /** The scales the table holds, once it exists. */
    public double[] scales() {
        if (type == Type.ODE && !solved) {
            tabulate();
        }
        double[] out = new double[q2s.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = Math.sqrt(q2s[i]);
        }
        return out;
    }

    /** Where the coupling is allowed to step, in GeV. */
    public List<Double> thresholdScales() {
        List<Double> out = new ArrayList<>();
        final Map<Integer, Double> where = thresholds.isEmpty() ? quarkMasses : thresholds;
        for (int q = 4; q <= 6; q++) {
            final Double at = where.get(q);
            if (at != null) {
                out.add(at);
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
            "alpha_s %s, order %d, alpha_s(MZ) = %.5f at MZ = %.4f GeV, %s flavors",
            type, order, alphaSmz, mz, scheme == Flavors.FIXED
                ? "fixed " + fixedFlavors : "variable");
    }

    /* ------------------------------------------------------------------ */
    /* Reading the info file                                               */
    /* ------------------------------------------------------------------ */

    private static boolean allPresent(Map<String, String> info, String prefix,
                                      String[] named) {
        for (String one : named) {
            if (!info.containsKey(prefix + one)) {
                return false;
            }
        }
        return true;
    }

    private static String text(Map<String, String> info, String key, String fallback) {
        final String said = info.get(key);
        return said == null ? fallback : said.trim();
    }

    private static double number(Map<String, String> info, String key, double fallback) {
        final String said = info.get(key);
        if (said == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(said.trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    /**
     * A list as the info file writes one.
     *
     * The format is YAML, so a list is in square brackets with commas, but some
     * sets write it as plain whitespace-separated numbers. Both are read: the
     * separators are simply treated as separators.
     */
    private static double[] list(Map<String, String> info, String key) {
        final String said = info.get(key);
        if (said == null) {
            return new double[0];
        }
        final String bare = said.trim().replace("[", " ").replace("]", " ").replace(",", " ");
        List<Double> out = new ArrayList<>();
        for (String word : bare.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            try {
                out.add(Double.parseDouble(word));
            } catch (NumberFormatException notANumber) {
                return new double[0];
            }
        }
        double[] values = new double[out.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = out.get(i);
        }
        return values;
    }

    private static double[] squared(double[] qs) {
        double[] out = new double[qs.length];
        for (int i = 0; i < qs.length; i++) {
            out[i] = qs[i] * qs[i];
        }
        return out;
    }
}
