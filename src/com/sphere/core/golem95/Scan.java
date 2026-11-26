package com.sphere.core.golem95;

import com.sphere.core.golem95.port.Cx;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.function.DoubleFunction;

/**
 * A quantity along a line of kinematics, computed on every processor at once. Each point carries its own
 * noise (the answer for the kinematics moved by 1e-15), so the points where Golem95's evaluation cannot be
 * trusted are said, not drawn as if they were the integral; where the curve turns fast (a threshold, a
 * peak), points are added between the others until it is followed; and where a two-particle threshold or
 * the leading Landau singularity is crossed, the scan says where.
 */
public final class Scan {

    private Scan() {
    }

    /**
     * One point: where, the value (null if Golem95 gave up), the relative noise, and why it failed.
     */
    public record Point(double x, Laurent value, double noise, String error) {

        public boolean ok() {
            return value != null && value.isFinite();
        }
    }

    public record Result(String quantity, String variable, List<Point> points, List<String> notes, long nanos) {

        public List<Point> good() {
            return points.stream().filter(Point::ok).toList();
        }
    }

    /** The noise above which a point is not drawn as the integral: about eight digits. */
    static final double UNRELIABLE = 1e-8;

    /**
     * @param at     the kinematics at each value of the variable
     * @param refine whether points are added where the curve turns fast
     */
    public static Result run(Quantity q, DoubleFunction<Kinematics> at, String variable, double from, double to, int count,
                             GolemEngine.Settings s, boolean refine) {
        if (count < 2 || count > 100_000) {
            throw new IllegalArgumentException("between 2 and 100000 points, not " + count);
        }
        if (!(from < to) && !(from > to)) {
            throw new IllegalArgumentException("the range is empty");
        }
        final long t0 = System.nanoTime();
        final double[] xs = new double[count];
        for (int i = 0; i < count; i++) {
            xs[i] = from + (to - from) * i / (count - 1);
        }
        final List<Point> points = new ArrayList<>(compute(q, at, xs, s));
        if (refine) {
            for (int pass = 0; pass < 3; pass++) {
                points.sort(Comparator.comparingDouble(Point::x));
                final double[] more = turns(points, Math.max(1, count / 4));
                if (more.length == 0) {
                    break;
                }
                points.addAll(compute(q, at, more, s));
            }
            points.sort(Comparator.comparingDouble(Point::x));
        }
        final List<String> notes = notes(points, at, variable, count, refine);
        return new Result(q.label(), variable, List.copyOf(points), notes, System.nanoTime() - t0);
    }

    private static List<Point> compute(Quantity q, DoubleFunction<Kinematics> at, double[] xs, GolemEngine.Settings s) {
        final List<Callable<Point>> tasks = new ArrayList<>();
        for (double x : xs) {
            tasks.add(() -> {
                final Kinematics k;
                try {
                    k = at.apply(x);
                } catch (RuntimeException e) {
                    return new Point(x, null, Double.NaN, e.getMessage());
                }
                final GolemEngine e = Golem95.engine(s);
                final Laurent v = q.on(e.set(k)).laurent();
                double noise = Double.NaN;
                try {
                    final Laurent moved = q.on(e.set(k.perturbed(new Random(Double.doubleToLongBits(x)),
                        Reliability.NOISE_DELTA))).laurent();
                    noise = Reliability.dev(moved, v, Reliability.scale(v));
                } catch (RuntimeException ignored) {
                    // the point stands; its noise is unknown
                }
                return new Point(x, v, noise, null);
            });
        }
        final List<Point> out = new ArrayList<>();
        final List<Golem95.Outcome<Point>> done = Golem95.all(tasks);
        for (int i = 0; i < xs.length; i++) {
            final Golem95.Outcome<Point> o = done.get(i);
            out.add(o.ok() ? o.value() : new Point(xs[i], null, Double.NaN,
                o.error().getMessage() == null ? o.error().toString() : o.error().getMessage()));
        }
        return out;
    }

    /** Midpoints of the intervals where the curve moves much more than it does elsewhere. */
    private static double[] turns(List<Point> points, int budget) {
        final List<double[]> steps = new ArrayList<>();
        double size = 0;
        for (Point p : points) {
            if (p.ok()) {
                size = Math.max(size, p.value().size());
            }
        }
        if (size == 0) {
            return new double[0];
        }
        final List<Double> moves = new ArrayList<>();
        for (int i = 1; i < points.size(); i++) {
            final Point a = points.get(i - 1);
            final Point b = points.get(i);
            if (!a.ok() || !b.ok()) {
                continue;
            }
            final double m = Math.max(Cx.abs(Cx.sub(a.value().c(), b.value().c())),
                Math.max(Cx.abs(Cx.sub(a.value().b(), b.value().b())), Cx.abs(Cx.sub(a.value().a(), b.value().a())))) / size;
            moves.add(m);
            steps.add(new double[]{m, 0.5 * (a.x() + b.x())});
        }
        if (moves.size() < 4) {
            return new double[0];
        }
        final double[] sorted = moves.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        final double median = sorted[sorted.length / 2];
        final double limit = Math.max(4 * median, 1e-3);
        steps.sort((u, v) -> Double.compare(v[0], u[0]));
        final List<Double> out = new ArrayList<>();
        for (double[] st : steps) {
            if (st[0] <= limit || out.size() >= budget) {
                break;
            }
            out.add(st[1]);
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static List<String> notes(List<Point> points, DoubleFunction<Kinematics> at, String variable, int asked,
                                      boolean refine) {
        final List<String> notes = new ArrayList<>();
        if (refine && points.size() > asked) {
            notes.add((points.size() - asked) + " points added where the curve turns fast.");
        }
        final boolean[] failed = new boolean[points.size()];
        final boolean[] bad = new boolean[points.size()];
        int nFailed = 0;
        int nBad = 0;
        String why = null;
        for (int i = 0; i < points.size(); i++) {
            final Point p = points.get(i);
            if (!p.ok()) {
                failed[i] = true;
                nFailed++;
                why = why == null ? p.error() : why;
            } else if (p.noise() > UNRELIABLE) {
                bad[i] = true;
                nBad++;
            }
        }
        if (nFailed > 0) {
            notes.add(String.format(Locale.ROOT, "Golem95 gave no answer at %d point%s (%s = %s)%s.", nFailed,
                nFailed == 1 ? "" : "s", variable, ranges(points, failed), why == null ? "" : ": " + why));
        }
        if (nBad > 0) {
            notes.add(String.format(Locale.ROOT, "%d point%s with fewer than about 8 correct digits (%s = %s): drawn, but "
                + "':golem check' at one says why, and rescues it.", nBad, nBad == 1 ? "" : "s", variable, ranges(points, bad)));
        }
        // where the structure of the integral changes along the line
        String last = "";
        for (Point p : points) {
            String here;
            try {
                final Kinematics.Analysis an = at.apply(p.x()).analyse();
                here = an.landau() ? "landau" : an.thresholds().isEmpty() ? "" : "threshold";
            } catch (RuntimeException e) {
                here = "";
            }
            if (!here.isEmpty() && !here.equals(last)) {
                notes.add(String.format(Locale.ROOT, "%s near %s = %s.", here.equals("threshold")
                    ? "A two-particle threshold is crossed" : "The leading Landau singularity (det S = 0) is crossed",
                    variable, Kinematics.num(p.x())));
            }
            last = here;
        }
        return notes;
    }

    /** The flagged points as ranges of consecutive ones: "1.5 to 2.25, 7", four at most. */
    private static String ranges(List<Point> points, boolean[] flagged) {
        final List<String> out = new ArrayList<>();
        int i = 0;
        int groups = 0;
        while (i < points.size()) {
            if (!flagged[i]) {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < points.size() && flagged[j + 1]) {
                j++;
            }
            groups++;
            if (out.size() < 4) {
                out.add(i == j ? Kinematics.num(points.get(i).x())
                    : Kinematics.num(points.get(i).x()) + " to " + Kinematics.num(points.get(j).x()));
            }
            i = j + 1;
        }
        return String.join(", ", out) + (groups > out.size() ? " ..." : "");
    }
}
